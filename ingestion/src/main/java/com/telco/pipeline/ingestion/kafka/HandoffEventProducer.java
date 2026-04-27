package com.telco.pipeline.ingestion.kafka;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes handoff events to Kafka with strict ordering guarantees.
 *
 * Handoff events are critical for network optimization — they must be delivered
 * in order per subscriber (keyed by IMSI hash) and with at-least-once semantics.
 * This producer uses idempotent writes and partitions by subscriber hash to
 * ensure per-subscriber ordering is maintained.
 *
 * Event flow: MME/AMF → S1AP/NGAP interface → Handoff Event Collector → this producer → Kafka
 */
public class HandoffEventProducer implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(HandoffEventProducer.class);

    private static final String TOPIC_HANDOFF_EVENTS = "telco.raw.handoff-events";
    private static final String TOPIC_HANDOFF_FAILURES = "telco.raw.handoff-failures";

    private final KafkaProducer<String, byte[]> producer;
    private final MetricRegistry metrics;
    private final Meter handoffEventRate;
    private final Meter handoffFailureRate;
    private final Timer publishLatency;
    private final AtomicBoolean running;

    public HandoffEventProducer(String bootstrapServers, String producerId,
                                MetricRegistry metricRegistry) {
        this.metrics = metricRegistry;
        this.handoffEventRate = metrics.meter("handoff-producer.event-rate");
        this.handoffFailureRate = metrics.meter("handoff-producer.failure-rate");
        this.publishLatency = metrics.timer("handoff-producer.publish-latency");
        this.running = new AtomicBoolean(true);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "handoff-event-" + producerId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        // Strict ordering: single in-flight request with idempotence
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);

        this.producer = new KafkaProducer<>(props);
        LOG.info("HandoffEventProducer initialized for {}", bootstrapServers);
    }

    /**
     * Publish a handoff event. Routes to the appropriate topic based on success/failure.
     *
     * @param imsiHash        subscriber identifier hash (partition key)
     * @param sourceTowerId   source cell tower
     * @param targetTowerId   target cell tower
     * @param handoffType     type of handoff (INTRA_FREQ, INTER_RAT, etc.)
     * @param success         whether the handoff succeeded
     * @param eventTimestamp   event timestamp in epoch ms
     * @param serializedEvent Avro-serialized handoff event bytes
     */
    public Future<RecordMetadata> publishHandoffEvent(
            String imsiHash, String sourceTowerId, String targetTowerId,
            String handoffType, boolean success, long eventTimestamp,
            byte[] serializedEvent) {

        if (!running.get()) {
            throw new IllegalStateException("Producer is shut down");
        }

        String topic = success ? TOPIC_HANDOFF_EVENTS : TOPIC_HANDOFF_FAILURES;
        String partitionKey = imsiHash;

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                topic, null, eventTimestamp, partitionKey, serializedEvent);

        record.headers()
                .add("source_tower", sourceTowerId.getBytes())
                .add("target_tower", targetTowerId.getBytes())
                .add("handoff_type", handoffType.getBytes())
                .add("success", boolToBytes(success))
                .add("event_version", "2".getBytes());

        Timer.Context timerCtx = publishLatency.time();
        handoffEventRate.mark();

        return producer.send(record, (metadata, exception) -> {
            timerCtx.stop();
            if (exception != null) {
                handoffFailureRate.mark();
                LOG.error("Failed to publish handoff event: src={}, tgt={}, type={}: {}",
                        sourceTowerId, targetTowerId, handoffType, exception.getMessage());
            } else {
                LOG.debug("Handoff event published: topic={}, partition={}, offset={}",
                        metadata.topic(), metadata.partition(), metadata.offset());
            }
        });
    }

    /**
     * Publish a ping-pong detection event. These are handoffs that reverse
     * within a short time window, indicating parameter misconfiguration.
     */
    public void publishPingPongEvent(String imsiHash, String towerA, String towerB,
                                     int pingPongCount, long windowMs,
                                     byte[] serializedEvent) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                "telco.derived.ping-pong-events", imsiHash, serializedEvent);

        record.headers()
                .add("tower_a", towerA.getBytes())
                .add("tower_b", towerB.getBytes())
                .add("count", ByteBuffer.allocate(4).putInt(pingPongCount).array())
                .add("window_ms", ByteBuffer.allocate(8).putLong(windowMs).array());

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                LOG.error("Failed to publish ping-pong event: {} <-> {}", towerA, towerB);
            }
        });
    }

    @Override
    public void close() throws IOException {
        if (running.compareAndSet(true, false)) {
            producer.flush();
            producer.close(java.time.Duration.ofSeconds(30));
            LOG.info("HandoffEventProducer shut down");
        }
    }

    private byte[] boolToBytes(boolean value) {
        return new byte[]{(byte) (value ? 1 : 0)};
    }
}
