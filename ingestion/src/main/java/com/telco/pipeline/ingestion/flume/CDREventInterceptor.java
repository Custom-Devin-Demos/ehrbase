package com.telco.pipeline.ingestion.flume;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.interceptor.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flume interceptor for Call Detail Records received from the voice core switch.
 *
 * CDRs arrive as semicolon-delimited records from the MSC/VoLTE IMS core.
 * This interceptor validates, enriches, and classifies CDRs for routing
 * to the appropriate HDFS directories.
 *
 * Input format (semicolon-delimited):
 *   CDR_ID;CALL_ID;CALLER_HASH;CALLEE_HASH;CALL_TYPE;SETUP_TS;CONNECT_TS;END_TS;
 *   DURATION;TERM_CAUSE;IS_DROPPED;ORIG_TOWER;ORIG_SECTOR;TERM_TOWER;TERM_SECTOR;
 *   HANDOFF_COUNT;AVG_RSSI;MIN_RSSI;AVG_SINR;MOS_SCORE
 */
public class CDREventInterceptor implements Interceptor {

    private static final Logger LOG = LoggerFactory.getLogger(CDREventInterceptor.class);
    private static final int MIN_FIELDS = 20;

    private final String sourceId;
    private final boolean enrichWithBilling;
    private long processedCount;
    private long droppedCount;
    private long errorCount;

    private CDREventInterceptor(String sourceId, boolean enrichWithBilling) {
        this.sourceId = sourceId;
        this.enrichWithBilling = enrichWithBilling;
        this.processedCount = 0;
        this.droppedCount = 0;
        this.errorCount = 0;
    }

    @Override
    public void initialize() {
        LOG.info("CDREventInterceptor initialized: source={}, billing={}",
                sourceId, enrichWithBilling);
    }

    @Override
    public Event intercept(Event event) {
        if (event == null || event.getBody() == null) {
            return null;
        }

        String rawLine = new String(event.getBody(), StandardCharsets.UTF_8).trim();
        if (rawLine.isEmpty() || rawLine.startsWith("#") || rawLine.startsWith("HDR")) {
            return null; // Skip comments and file headers
        }

        String[] fields = rawLine.split(";");
        if (fields.length < MIN_FIELDS) {
            errorCount++;
            LOG.warn("Malformed CDR, expected >={} fields, got {}", MIN_FIELDS, fields.length);
            return null;
        }

        try {
            String cdrId = fields[0].trim();
            String callId = fields[1].trim();
            String callerHash = fields[2].trim();
            String callType = fields[4].trim();
            long setupTimestamp = Long.parseLong(fields[5].trim());
            long endTimestamp = Long.parseLong(fields[7].trim());
            int duration = Integer.parseInt(fields[8].trim());
            String termCause = fields[9].trim();
            boolean isDropped = "1".equals(fields[10].trim()) || "true".equalsIgnoreCase(fields[10].trim());
            String origTower = fields[11].trim();
            int handoffCount = Integer.parseInt(fields[15].trim());

            // Validate timestamps
            if (endTimestamp < setupTimestamp) {
                LOG.warn("Invalid CDR timestamps: end < setup for cdr_id={}", cdrId);
                return null;
            }

            // Validate duration consistency
            long calculatedDuration = (endTimestamp - setupTimestamp) / 1000;
            if (Math.abs(calculatedDuration - duration) > 2) {
                LOG.debug("Duration mismatch for {}: reported={}, calculated={}",
                        cdrId, duration, calculatedDuration);
            }

            Map<String, String> headers = event.getHeaders();
            headers.put("cdr_id", cdrId);
            headers.put("call_id", callId);
            headers.put("caller_hash", callerHash);
            headers.put("call_type", callType);
            headers.put("is_dropped", String.valueOf(isDropped));
            headers.put("originating_tower", origTower);
            headers.put("handoff_count", String.valueOf(handoffCount));
            headers.put("source_id", sourceId);
            headers.put("ingest_time", String.valueOf(System.currentTimeMillis()));

            // Date partitioning
            String datePartition = deriveDatePartition(setupTimestamp);
            headers.put("date_partition", datePartition);

            // Call classification for tiered processing
            String callCategory = classifyCall(callType, isDropped, duration, handoffCount);
            headers.put("call_category", callCategory);

            // Priority routing
            if (isDropped) {
                headers.put("priority", "HIGH");
                droppedCount++;
            } else if ("EMERGENCY".equals(callType)) {
                headers.put("priority", "CRITICAL");
            } else {
                headers.put("priority", "NORMAL");
            }

            // Billing enrichment
            if (enrichWithBilling) {
                String billingCategory = deriveBillingCategory(callType, duration);
                headers.put("billing_category", billingCategory);
            }

            processedCount++;
            if (processedCount % 100_000 == 0) {
                LOG.info("CDR interceptor stats: processed={}, dropped={}, errors={}",
                        processedCount, droppedCount, errorCount);
            }

            return event;

        } catch (NumberFormatException e) {
            errorCount++;
            LOG.warn("Failed to parse CDR numeric field", e);
            return null;
        }
    }

    @Override
    public List<Event> intercept(List<Event> events) {
        List<Event> result = new ArrayList<>(events.size());
        for (Event event : events) {
            Event intercepted = intercept(event);
            if (intercepted != null) {
                result.add(intercepted);
            }
        }
        return result;
    }

    @Override
    public void close() {
        LOG.info("CDREventInterceptor closing. Final stats: processed={}, dropped={}, errors={}",
                processedCount, droppedCount, errorCount);
    }

    private String deriveDatePartition(long timestampMs) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestampMs);
        return instant.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    private String classifyCall(String callType, boolean isDropped,
                                int duration, int handoffCount) {
        if (isDropped) return "dropped";
        if ("EMERGENCY".equals(callType)) return "emergency";
        if (duration == 0) return "missed";
        if (duration < 5) return "short";
        if (handoffCount > 10) return "high_mobility";
        return "normal";
    }

    private String deriveBillingCategory(String callType, int duration) {
        if (callType.startsWith("VOLTE") || callType.startsWith("VOWIFI")) {
            return "DATA_VOICE";
        }
        if (duration <= 0) return "NO_CHARGE";
        if (duration <= 60) return "LOCAL_SHORT";
        return "LOCAL_STANDARD";
    }

    public static class Builder implements Interceptor.Builder {
        private String sourceId = "msc-unknown";
        private boolean enrichWithBilling = false;

        @Override
        public Interceptor build() {
            return new CDREventInterceptor(sourceId, enrichWithBilling);
        }

        @Override
        public void configure(Context context) {
            sourceId = context.getString("sourceId", "msc-unknown");
            enrichWithBilling = context.getBoolean("enrichWithBilling", false);
        }
    }
}
