package com.telco.pipeline.hbase.dao;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * DAO for the telco:active_calls HBase table.
 * Tracks currently active calls for real-time monitoring and
 * handoff-aware call quality assessment.
 */
public class ActiveCallDAO implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(ActiveCallDAO.class);

    private static final TableName TABLE_NAME = TableName.valueOf("telco", "active_calls");
    private static final byte[] CF_CALL = Bytes.toBytes("call");
    private static final byte[] CF_QUALITY = Bytes.toBytes("quality");
    private static final byte[] CF_HANDOFF = Bytes.toBytes("handoff_history");

    private final Connection connection;
    private final BufferedMutator mutator;

    public ActiveCallDAO(Configuration conf) throws IOException {
        this.connection = ConnectionFactory.createConnection(conf);
        this.mutator = connection.getBufferedMutator(
                new BufferedMutatorParams(TABLE_NAME)
                        .writeBufferSize(2 * 1024 * 1024));
    }

    /**
     * Register a new active call.
     */
    public void registerCall(String callId, String callerHash, String calleeHash,
                             String callType, String towerId, int sectorId,
                             long setupTimestamp) throws IOException {
        Put put = new Put(Bytes.toBytes(callId));
        put.addColumn(CF_CALL, Bytes.toBytes("caller_hash"), Bytes.toBytes(callerHash));
        put.addColumn(CF_CALL, Bytes.toBytes("callee_hash"), Bytes.toBytes(calleeHash));
        put.addColumn(CF_CALL, Bytes.toBytes("call_type"), Bytes.toBytes(callType));
        put.addColumn(CF_CALL, Bytes.toBytes("current_tower"), Bytes.toBytes(towerId));
        put.addColumn(CF_CALL, Bytes.toBytes("current_sector"), Bytes.toBytes(sectorId));
        put.addColumn(CF_CALL, Bytes.toBytes("setup_ts"), Bytes.toBytes(setupTimestamp));
        put.addColumn(CF_CALL, Bytes.toBytes("status"), Bytes.toBytes("ACTIVE"));
        put.addColumn(CF_CALL, Bytes.toBytes("handoff_count"), Bytes.toBytes(0));

        mutator.mutate(put);
    }

    /**
     * Update call location after a handoff.
     */
    public void recordHandoff(String callId, String newTowerId, int newSectorId,
                              String handoffType, boolean success,
                              long handoffTimestamp) throws IOException {
        Put put = new Put(Bytes.toBytes(callId));
        put.addColumn(CF_CALL, Bytes.toBytes("current_tower"), Bytes.toBytes(newTowerId));
        put.addColumn(CF_CALL, Bytes.toBytes("current_sector"), Bytes.toBytes(newSectorId));

        // Record in handoff history (versioned column family)
        String handoffEntry = String.format("%s|%s|%d|%s",
                newTowerId, handoffType, handoffTimestamp, success ? "OK" : "FAIL");
        put.addColumn(CF_HANDOFF, Bytes.toBytes("event"), handoffTimestamp,
                Bytes.toBytes(handoffEntry));

        mutator.mutate(put);

        // Increment handoff counter
        try (Table table = connection.getTable(TABLE_NAME)) {
            table.incrementColumnValue(Bytes.toBytes(callId),
                    CF_CALL, Bytes.toBytes("handoff_count"), 1);
        }
    }

    /**
     * Update call quality metrics (called periodically during active calls).
     */
    public void updateQuality(String callId, double currentRsrp, double currentSinr,
                              double mos, double jitter, double packetLoss,
                              long timestamp) throws IOException {
        Put put = new Put(Bytes.toBytes(callId));
        put.addColumn(CF_QUALITY, Bytes.toBytes("rsrp"), Bytes.toBytes(currentRsrp));
        put.addColumn(CF_QUALITY, Bytes.toBytes("sinr"), Bytes.toBytes(currentSinr));
        put.addColumn(CF_QUALITY, Bytes.toBytes("mos"), Bytes.toBytes(mos));
        put.addColumn(CF_QUALITY, Bytes.toBytes("jitter_ms"), Bytes.toBytes(jitter));
        put.addColumn(CF_QUALITY, Bytes.toBytes("packet_loss"), Bytes.toBytes(packetLoss));
        put.addColumn(CF_QUALITY, Bytes.toBytes("update_ts"), Bytes.toBytes(timestamp));

        mutator.mutate(put);
    }

    /**
     * Mark a call as completed.
     */
    public void completeCall(String callId, String terminationCause,
                             boolean isDropped, long endTimestamp) throws IOException {
        Put put = new Put(Bytes.toBytes(callId));
        put.addColumn(CF_CALL, Bytes.toBytes("status"),
                Bytes.toBytes(isDropped ? "DROPPED" : "COMPLETED"));
        put.addColumn(CF_CALL, Bytes.toBytes("term_cause"),
                Bytes.toBytes(terminationCause));
        put.addColumn(CF_CALL, Bytes.toBytes("end_ts"), Bytes.toBytes(endTimestamp));

        mutator.mutate(put);
    }

    /**
     * Get active call details.
     */
    public Map<String, Object> getCall(String callId) throws IOException {
        Get get = new Get(Bytes.toBytes(callId));
        get.addFamily(CF_CALL);
        get.addFamily(CF_QUALITY);

        try (Table table = connection.getTable(TABLE_NAME)) {
            Result result = table.get(get);
            if (result.isEmpty()) return null;

            Map<String, Object> call = new HashMap<>();
            call.put("call_id", callId);
            byte[] status = result.getValue(CF_CALL, Bytes.toBytes("status"));
            if (status != null) call.put("status", Bytes.toString(status));

            byte[] tower = result.getValue(CF_CALL, Bytes.toBytes("current_tower"));
            if (tower != null) call.put("current_tower", Bytes.toString(tower));

            byte[] mos = result.getValue(CF_QUALITY, Bytes.toBytes("mos"));
            if (mos != null) call.put("mos", Bytes.toDouble(mos));

            return call;
        }
    }

    @Override
    public void close() throws IOException {
        mutator.flush();
        mutator.close();
        connection.close();
    }
}
