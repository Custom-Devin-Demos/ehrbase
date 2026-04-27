package com.telco.pipeline.ingestion.kafka;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.telco.pipeline.ingestion.kafka.serializers.TowerSignalAvroSerializer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-throughput Kafka producer for tower signal measurements.
 *
 * Receives raw signal data from RAN controllers via the backhaul network
 * and publishes to the tower-signals Kafka topic. Supports partitioning
 * by tower ID for ordered per-tower processing downstream.
 *
 * Typical deployment: one producer instance per regional aggregation point,
 * handling 50k-200k signals/sec depending on tower density.
 */
public class TowerSignalProducer implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(TowerSignalProducer.class);

    private static final String TOPIC_TOWER_SIGNALS = "telco.raw.tower-signals";
    private static final String CONFIG_FILE = "producer-tower-signal.properties";
    private static final int FLUSH_INTERVAL_MS = 100;
    private static final int LINGER_MS = 5;
    private static final int BATCH_SIZE = 65536;
    private static final int BUFFER_MEMORY = 67108864; // 64MB
    private static final String COMPRESSION_TYPE = "lz4";

    private final KafkaProducer<String, byte[]> producer;
    private final MetricRegistry metrics;
    private final Meter sendRate;
    private final Meter failureRate;
    private final Timer sendLatency;
    private final Counter inFlightCount;
    private final AtomicBoolean running;
    private final AtomicLong sequenceNumber;
    private final Map<String, AtomicLong> perTowerSequence;
    private final ExecutorService callbackExecutor;
    private final TowerSignalAvroSerializer serializer;

    public TowerSignalProducer(String bootstrapServers, String producerId) {
        this(bootstrapServers, producerId, new MetricRegistry());
    }

    public TowerSignalProducer(String bootstrapServers, String producerId,
                               MetricRegistry metricRegistry) {
        this.metrics = metricRegistry;
        this.sendRate = metrics.meter("tower-signal-producer.send-rate");
        this.failureRate = metrics.meter("tower-signal-producer.failure-rate");
        this.sendLatency = metrics.timer("tower-signal-producer.send-latency");
        this.inFlightCount = metrics.counter("tower-signal-producer.in-flight");
        this.running = new AtomicBoolean(true);
        this.sequenceNumber = new AtomicLong(0);
        this.perTowerSequence = new ConcurrentHashMap<>();
        this.callbackExecutor = Executors.newFixedThreadPool(4,
                r -> new Thread(r, "signal-producer-callback-" + r.hashCode()));
        this.serializer = new TowerSignalAvroSerializer();

        Properties props = loadBaseProperties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "tower-signal-" + producerId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, BATCH_SIZE);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, BUFFER_MEMORY);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, COMPRESSION_TYPE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        this.producer = new KafkaProducer<>(props);
        LOG.info("TowerSignalProducer initialized: bootstrap={}, id={}", bootstrapServers, producerId);
    }

    /**
     * Send a tower signal measurement to Kafka.
     *
     * @param towerId      tower identifier used as partition key
     * @param sectorId     sector index for sub-partitioning
     * @param timestampMs  measurement timestamp
     * @param signalData   raw signal measurement map
     */
    public void send(String towerId, int sectorId, long timestampMs,
                     Map<String, Object> signalData) {
        if (!running.get()) {
            throw new IllegalStateException("Producer is shut down");
        }

        String partitionKey = towerId + ":" + sectorId;
        long seq = perTowerSequence
                .computeIfAbsent(towerId, k -> new AtomicLong(0))
                .incrementAndGet();

        signalData.put("_sequence", seq);
        signalData.put("_ingest_timestamp_ms", System.currentTimeMillis());
        signalData.put("tower_id", towerId);
        signalData.put("sector_id", sectorId);
        signalData.put("timestamp_ms", timestampMs);

        byte[] payload = serializer.serialize(signalData);

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                TOPIC_TOWER_SIGNALS, null, timestampMs, partitionKey, payload);

        record.headers()
                .add("tower_id", towerId.getBytes())
                .add("sector_id", String.valueOf(sectorId).getBytes())
                .add("seq", String.valueOf(seq).getBytes())
                .add("source_region", getRegionFromTowerId(towerId).getBytes());

        inFlightCount.inc();
        Timer.Context timerCtx = sendLatency.time();

        producer.send(record, new SignalSendCallback(towerId, seq, timerCtx));
        sendRate.mark();
    }

    /**
     * Send a batch of signals from the same tower. Optimizes serialization
     * by reusing the schema across the batch.
     */
    public void sendBatch(String towerId, java.util.List<Map<String, Object>> signals) {
        if (signals == null || signals.isEmpty()) {
            return;
        }

        LOG.debug("Sending batch of {} signals for tower {}", signals.size(), towerId);
        for (Map<String, Object> signal : signals) {
            int sectorId = ((Number) signal.getOrDefault("sector_id", 0)).intValue();
            long timestampMs = ((Number) signal.getOrDefault("timestamp_ms",
                    System.currentTimeMillis())).longValue();
            send(towerId, sectorId, timestampMs, signal);
        }
    }

    public void flush() {
        producer.flush();
    }

    @Override
    public void close() throws IOException {
        if (running.compareAndSet(true, false)) {
            LOG.info("Shutting down TowerSignalProducer...");
            producer.flush();
            producer.close(java.time.Duration.ofSeconds(30));
            callbackExecutor.shutdown();
            try {
                if (!callbackExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    callbackExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                callbackExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOG.info("TowerSignalProducer shut down. Total sent: {}",
                    sequenceNumber.get());
        }
    }

    private String getRegionFromTowerId(String towerId) {
        // Tower ID format: MCC-MNC-LAC-CID
        // Extract LAC (Location Area Code) to determine region
        String[] parts = towerId.split("-");
        if (parts.length >= 3) {
            int lac = Integer.parseInt(parts[2]);
            if (lac < 1000) return "NORTHEAST";
            if (lac < 2000) return "SOUTHEAST";
            if (lac < 3000) return "MIDWEST";
            if (lac < 4000) return "SOUTHWEST";
            return "WEST";
        }
        return "UNKNOWN";
    }

    private Properties loadBaseProperties() {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            LOG.warn("Could not load {}, using defaults", CONFIG_FILE, e);
        }
        return props;
    }

    private class SignalSendCallback implements Callback {
        private final String towerId;
        private final long sequence;
        private final Timer.Context timerCtx;

        SignalSendCallback(String towerId, long sequence, Timer.Context timerCtx) {
            this.towerId = towerId;
            this.sequence = sequence;
            this.timerCtx = timerCtx;
        }

        @Override
        public void onCompletion(RecordMetadata metadata, Exception exception) {
            timerCtx.stop();
            inFlightCount.dec();
            sequenceNumber.incrementAndGet();

            if (exception != null) {
                failureRate.mark();
                LOG.error("Failed to send signal for tower {} seq {}: {}",
                        towerId, sequence, exception.getMessage());
            } else {
                LOG.trace("Signal sent: tower={}, seq={}, partition={}, offset={}",
                        towerId, sequence, metadata.partition(), metadata.offset());
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: TowerSignalProducer <bootstrap-servers> <producer-id>");
            System.exit(1);
        }

        String bootstrapServers = args[0];
        String producerId = args[1];

        try (TowerSignalProducer producer = new TowerSignalProducer(bootstrapServers, producerId)) {
            LOG.info("Tower signal producer started. Waiting for signals...");
            // In production, this would receive signals from the RAN controller interface
            Thread.currentThread().join();
        } catch (Exception e) {
            LOG.error("Fatal error in TowerSignalProducer", e);
            System.exit(1);
        }
    }
}
