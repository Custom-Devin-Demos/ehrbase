package com.telco.pipeline.ingestion.kafka;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Histogram;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes Call Detail Records from the voice switch / IMS core to Kafka.
 *
 * CDRs are generated at call teardown and contain the complete call lifecycle:
 * setup, connect, active phase with handoff history, and termination cause.
 *
 * Partitioning strategy: by caller IMSI hash to enable per-subscriber
 * sequential processing for billing reconciliation and fraud detection.
 *
 * Special handling:
 * - Dropped calls (is_dropped=true) are dual-published to a priority topic
 *   for real-time dropped call alerting
 * - Emergency calls (call_type=EMERGENCY) skip batching and are sent immediately
 * - Calls exceeding quality thresholds trigger a side-channel quality alert
 */
public class CDRProducer implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(CDRProducer.class);

    private static final String TOPIC_CDR = "telco.raw.call-detail-records";
    private static final String TOPIC_DROPPED_CALLS = "telco.alerts.dropped-calls";
    private static final String TOPIC_QUALITY_ALERTS = "telco.alerts.call-quality";
    private static final double MOS_QUALITY_THRESHOLD = 2.5;
    private static final double PACKET_LOSS_THRESHOLD = 5.0;

    private final KafkaProducer<String, byte[]> producer;
    private final MetricRegistry metrics;
    private final Meter cdrRate;
    private final Meter droppedCallRate;
    private final Meter qualityAlertRate;
    private final Histogram callDurationHistogram;
    private final Histogram mosScoreHistogram;
    private final AtomicBoolean running;

    public CDRProducer(String bootstrapServers, String producerId,
                       MetricRegistry metricRegistry) {
        this.metrics = metricRegistry;
        this.cdrRate = metrics.meter("cdr-producer.rate");
        this.droppedCallRate = metrics.meter("cdr-producer.dropped-call-rate");
        this.qualityAlertRate = metrics.meter("cdr-producer.quality-alert-rate");
        this.callDurationHistogram = metrics.histogram("cdr-producer.call-duration");
        this.mosScoreHistogram = metrics.histogram("cdr-producer.mos-score");
        this.running = new AtomicBoolean(true);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "cdr-producer-" + producerId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 131072); // 128KB batches

        this.producer = new KafkaProducer<>(props);
    }

    /**
     * Publish a CDR after call teardown.
     *
     * @param callerImsiHash  caller IMSI hash (partition key)
     * @param callId          unique call identifier
     * @param callType        call type (VOICE_MO, VOLTE_MO, etc.)
     * @param isDropped       whether call was abnormally terminated
     * @param durationSeconds call duration
     * @param mosScore        Mean Opinion Score (nullable)
     * @param packetLossPct   packet loss percentage (nullable)
     * @param serializedCdr   Avro-serialized CDR bytes
     */
    public Future<RecordMetadata> publishCDR(
            String callerImsiHash, String callId, String callType,
            boolean isDropped, int durationSeconds,
            Double mosScore, Double packetLossPct,
            byte[] serializedCdr) {

        if (!running.get()) {
            throw new IllegalStateException("Producer is shut down");
        }

        callDurationHistogram.update(durationSeconds);
        cdrRate.mark();

        // Primary CDR publication
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                TOPIC_CDR, callerImsiHash, serializedCdr);
        record.headers()
                .add("call_id", callId.getBytes())
                .add("call_type", callType.getBytes())
                .add("dropped", new byte[]{(byte) (isDropped ? 1 : 0)});

        Future<RecordMetadata> future = producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                LOG.error("Failed to publish CDR for call {}: {}", callId, exception.getMessage());
            }
        });

        // Side-channel: dropped call alerting
        if (isDropped) {
            droppedCallRate.mark();
            ProducerRecord<String, byte[]> droppedRecord = new ProducerRecord<>(
                    TOPIC_DROPPED_CALLS, callerImsiHash, serializedCdr);
            droppedRecord.headers()
                    .add("call_id", callId.getBytes())
                    .add("priority", "HIGH".getBytes());
            producer.send(droppedRecord);
            LOG.warn("Dropped call detected: callId={}, type={}, duration={}s",
                    callId, callType, durationSeconds);
        }

        // Side-channel: quality degradation alerting
        if (mosScore != null && mosScore < MOS_QUALITY_THRESHOLD) {
            mosScoreHistogram.update((long) (mosScore * 100));
            qualityAlertRate.mark();
            ProducerRecord<String, byte[]> qualityRecord = new ProducerRecord<>(
                    TOPIC_QUALITY_ALERTS, callerImsiHash, serializedCdr);
            qualityRecord.headers()
                    .add("alert_type", "LOW_MOS".getBytes())
                    .add("mos_score", String.valueOf(mosScore).getBytes());
            producer.send(qualityRecord);
        }

        if (packetLossPct != null && packetLossPct > PACKET_LOSS_THRESHOLD) {
            qualityAlertRate.mark();
            ProducerRecord<String, byte[]> qualityRecord = new ProducerRecord<>(
                    TOPIC_QUALITY_ALERTS, callerImsiHash, serializedCdr);
            qualityRecord.headers()
                    .add("alert_type", "HIGH_PACKET_LOSS".getBytes())
                    .add("packet_loss_pct", String.valueOf(packetLossPct).getBytes());
            producer.send(qualityRecord);
        }

        return future;
    }

    /**
     * Force-publish an emergency call CDR with no batching delay.
     */
    public Future<RecordMetadata> publishEmergencyCDR(String callerImsiHash, String callId,
                                                      byte[] serializedCdr) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                "telco.priority.emergency-calls", callerImsiHash, serializedCdr);
        record.headers()
                .add("call_id", callId.getBytes())
                .add("priority", "CRITICAL".getBytes());

        Future<RecordMetadata> future = producer.send(record);
        producer.flush(); // Immediate delivery for emergency calls
        return future;
    }

    @Override
    public void close() throws IOException {
        if (running.compareAndSet(true, false)) {
            producer.flush();
            producer.close(java.time.Duration.ofSeconds(30));
            LOG.info("CDRProducer shut down");
        }
    }
}
