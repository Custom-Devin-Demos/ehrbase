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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flume interceptor for tower signal events received via syslog from RAN controllers.
 *
 * Responsibilities:
 * 1. Parse raw syslog-formatted signal data into structured headers
 * 2. Validate signal strength ranges and reject out-of-bounds measurements
 * 3. Add routing headers for downstream HDFS partitioning (region, date)
 * 4. Enrich with ingestion timestamp and source identification
 * 5. Filter duplicate measurements within a configurable dedup window
 *
 * Expected input format (pipe-delimited):
 *   TOWER_ID|SECTOR|TIMESTAMP|RSSI|SNR|RSRP|RSRQ|SINR|CQI|CONNECTED_UES|...
 */
public class TowerSignalInterceptor implements Interceptor {

    private static final Logger LOG = LoggerFactory.getLogger(TowerSignalInterceptor.class);

    private static final Pattern TOWER_ID_PATTERN = Pattern.compile("^\\d{3}-\\d{2,3}-\\d{1,5}-\\d{1,10}$");
    private static final double MIN_RSSI_DBM = -140.0;
    private static final double MAX_RSSI_DBM = -20.0;
    private static final double MIN_RSRP_DBM = -156.0;
    private static final double MAX_RSRP_DBM = -31.0;

    private final String sourceId;
    private final boolean enableDedup;
    private final int dedupWindowSeconds;
    private final boolean strictValidation;

    // Simple dedup cache: tower_id:sector:timestamp_bucket -> seen
    private final java.util.concurrent.ConcurrentHashMap<String, Long> dedupCache;
    private long lastCacheCleanup;

    private TowerSignalInterceptor(String sourceId, boolean enableDedup,
                                   int dedupWindowSeconds, boolean strictValidation) {
        this.sourceId = sourceId;
        this.enableDedup = enableDedup;
        this.dedupWindowSeconds = dedupWindowSeconds;
        this.strictValidation = strictValidation;
        this.dedupCache = new java.util.concurrent.ConcurrentHashMap<>();
        this.lastCacheCleanup = System.currentTimeMillis();
    }

    @Override
    public void initialize() {
        LOG.info("TowerSignalInterceptor initialized: source={}, dedup={}, strictValidation={}",
                sourceId, enableDedup, strictValidation);
    }

    @Override
    public Event intercept(Event event) {
        if (event == null || event.getBody() == null) {
            return null;
        }

        String rawLine = new String(event.getBody(), StandardCharsets.UTF_8).trim();
        if (rawLine.isEmpty() || rawLine.startsWith("#")) {
            return null;
        }

        String[] fields = rawLine.split("\\|");
        if (fields.length < 10) {
            LOG.warn("Malformed signal record, expected >=10 fields, got {}: {}",
                    fields.length, truncate(rawLine, 100));
            return null;
        }

        try {
            String towerId = fields[0].trim();
            int sectorId = Integer.parseInt(fields[1].trim());
            long timestamp = Long.parseLong(fields[2].trim());
            double rssi = Double.parseDouble(fields[3].trim());
            double snr = Double.parseDouble(fields[4].trim());
            double rsrp = Double.parseDouble(fields[5].trim());
            double rsrq = Double.parseDouble(fields[6].trim());
            double sinr = Double.parseDouble(fields[7].trim());
            int cqi = Integer.parseInt(fields[8].trim());
            int connectedUes = Integer.parseInt(fields[9].trim());

            // Validate tower ID format
            Matcher matcher = TOWER_ID_PATTERN.matcher(towerId);
            if (!matcher.matches()) {
                LOG.warn("Invalid tower ID format: {}", towerId);
                return null;
            }

            // Validate signal ranges
            if (strictValidation) {
                if (rssi < MIN_RSSI_DBM || rssi > MAX_RSSI_DBM) {
                    LOG.debug("RSSI out of range for tower {}: {} dBm", towerId, rssi);
                    return null;
                }
                if (rsrp < MIN_RSRP_DBM || rsrp > MAX_RSRP_DBM) {
                    LOG.debug("RSRP out of range for tower {}: {} dBm", towerId, rsrp);
                    return null;
                }
                if (cqi < 0 || cqi > 15) {
                    LOG.debug("CQI out of range for tower {}: {}", towerId, cqi);
                    return null;
                }
            }

            // Deduplication
            if (enableDedup) {
                long bucket = timestamp / (dedupWindowSeconds * 1000L);
                String dedupKey = towerId + ":" + sectorId + ":" + bucket;
                Long existing = dedupCache.putIfAbsent(dedupKey, timestamp);
                if (existing != null) {
                    LOG.trace("Duplicate signal suppressed: {}", dedupKey);
                    return null;
                }
                periodicCacheCleanup();
            }

            // Enrich event headers
            Map<String, String> headers = event.getHeaders();
            headers.put("tower_id", towerId);
            headers.put("sector_id", String.valueOf(sectorId));
            headers.put("timestamp", String.valueOf(timestamp));
            headers.put("source_id", sourceId);
            headers.put("ingest_time", String.valueOf(System.currentTimeMillis()));

            // Routing headers for HDFS partitioning
            String region = deriveRegion(towerId);
            String datePartition = deriveDatePartition(timestamp);
            headers.put("region", region);
            headers.put("date_partition", datePartition);
            headers.put("hour_partition", deriveHourPartition(timestamp));

            // Signal quality classification for tiered storage
            String qualityTier = classifySignalQuality(rsrp, sinr, cqi);
            headers.put("quality_tier", qualityTier);

            return event;

        } catch (NumberFormatException e) {
            LOG.warn("Failed to parse signal record: {}", truncate(rawLine, 100), e);
            return null;
        }
    }

    @Override
    public List<Event> intercept(List<Event> events) {
        List<Event> intercepted = new ArrayList<>(events.size());
        for (Event event : events) {
            Event result = intercept(event);
            if (result != null) {
                intercepted.add(result);
            }
        }
        return intercepted;
    }

    @Override
    public void close() {
        dedupCache.clear();
        LOG.info("TowerSignalInterceptor closed");
    }

    private String deriveRegion(String towerId) {
        String[] parts = towerId.split("-");
        if (parts.length >= 3) {
            int lac = Integer.parseInt(parts[2]);
            if (lac < 1000) return "northeast";
            if (lac < 2000) return "southeast";
            if (lac < 3000) return "midwest";
            if (lac < 4000) return "southwest";
            return "west";
        }
        return "unknown";
    }

    private String deriveDatePartition(long timestampMs) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestampMs);
        java.time.LocalDate date = instant.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return date.toString(); // yyyy-MM-dd
    }

    private String deriveHourPartition(long timestampMs) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestampMs);
        int hour = instant.atZone(java.time.ZoneOffset.UTC).getHour();
        return String.format("%02d", hour);
    }

    private String classifySignalQuality(double rsrp, double sinr, int cqi) {
        if (rsrp >= -80 && sinr >= 20 && cqi >= 12) return "excellent";
        if (rsrp >= -90 && sinr >= 13 && cqi >= 7) return "good";
        if (rsrp >= -100 && sinr >= 0 && cqi >= 4) return "fair";
        return "poor";
    }

    private void periodicCacheCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCacheCleanup > 60_000) { // Every minute
            long cutoff = now - (dedupWindowSeconds * 1000L * 2);
            dedupCache.entrySet().removeIf(e -> e.getValue() < cutoff);
            lastCacheCleanup = now;
        }
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Flume interceptor builder.
     */
    public static class Builder implements Interceptor.Builder {

        private String sourceId = "unknown";
        private boolean enableDedup = true;
        private int dedupWindowSeconds = 5;
        private boolean strictValidation = true;

        @Override
        public Interceptor build() {
            return new TowerSignalInterceptor(sourceId, enableDedup,
                    dedupWindowSeconds, strictValidation);
        }

        @Override
        public void configure(Context context) {
            sourceId = context.getString("sourceId", "unknown");
            enableDedup = context.getBoolean("enableDedup", true);
            dedupWindowSeconds = context.getInteger("dedupWindowSeconds", 5);
            strictValidation = context.getBoolean("strictValidation", true);
        }
    }
}
