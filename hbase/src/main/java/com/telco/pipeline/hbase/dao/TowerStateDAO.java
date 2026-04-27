package com.telco.pipeline.hbase.dao;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Data Access Object for the telco:tower_state HBase table.
 * Provides low-latency read/write access to current tower operational state.
 *
 * Used by:
 * - Spark Streaming real-time signal processor (writes)
 * - NOC dashboard API (reads)
 * - Handoff prediction model scoring (reads)
 * - Anomaly detection alerting (writes)
 */
public class TowerStateDAO implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(TowerStateDAO.class);

    private static final TableName TABLE_NAME = TableName.valueOf("telco", "tower_state");
    private static final byte[] CF_SIGNAL = Bytes.toBytes("signal");
    private static final byte[] CF_CAPACITY = Bytes.toBytes("capacity");
    private static final byte[] CF_STATUS = Bytes.toBytes("status");

    // Signal column qualifiers
    private static final byte[] COL_RSRP = Bytes.toBytes("rsrp");
    private static final byte[] COL_RSRQ = Bytes.toBytes("rsrq");
    private static final byte[] COL_SINR = Bytes.toBytes("sinr");
    private static final byte[] COL_RSSI = Bytes.toBytes("rssi");
    private static final byte[] COL_CQI = Bytes.toBytes("cqi");
    private static final byte[] COL_INTERFERENCE = Bytes.toBytes("interference");

    // Capacity column qualifiers
    private static final byte[] COL_PRB_UTIL = Bytes.toBytes("prb_util");
    private static final byte[] COL_CONNECTED_UES = Bytes.toBytes("conn_ues");
    private static final byte[] COL_DL_THROUGHPUT = Bytes.toBytes("dl_mbps");
    private static final byte[] COL_UL_THROUGHPUT = Bytes.toBytes("ul_mbps");

    // Status column qualifiers
    private static final byte[] COL_QUALITY_CLASS = Bytes.toBytes("quality");
    private static final byte[] COL_CAPACITY_STATUS = Bytes.toBytes("cap_status");
    private static final byte[] COL_LAST_UPDATE = Bytes.toBytes("last_update");
    private static final byte[] COL_ANOMALY_FLAG = Bytes.toBytes("anomaly");

    private final Connection connection;
    private final BufferedMutator mutator;

    public TowerStateDAO(Configuration conf) throws IOException {
        this.connection = ConnectionFactory.createConnection(conf);
        BufferedMutatorParams params = new BufferedMutatorParams(TABLE_NAME)
                .writeBufferSize(4 * 1024 * 1024) // 4MB write buffer
                .setWriteBufferPeriodicFlushTimeoutMs(1000); // Flush every second
        this.mutator = connection.getBufferedMutator(params);
    }

    /**
     * Update the current signal state for a tower sector.
     */
    public void updateSignalState(String towerId, int sectorId,
                                  double rsrp, double rsrq, double sinr,
                                  double rssi, int cqi, double interference,
                                  long timestampMs) throws IOException {
        byte[] rowKey = makeRowKey(towerId, sectorId);

        Put put = new Put(rowKey, timestampMs);
        put.addColumn(CF_SIGNAL, COL_RSRP, Bytes.toBytes(rsrp));
        put.addColumn(CF_SIGNAL, COL_RSRQ, Bytes.toBytes(rsrq));
        put.addColumn(CF_SIGNAL, COL_SINR, Bytes.toBytes(sinr));
        put.addColumn(CF_SIGNAL, COL_RSSI, Bytes.toBytes(rssi));
        put.addColumn(CF_SIGNAL, COL_CQI, Bytes.toBytes(cqi));
        put.addColumn(CF_SIGNAL, COL_INTERFERENCE, Bytes.toBytes(interference));

        // Update quality classification
        String quality = classifySignal(rsrp, sinr, cqi);
        put.addColumn(CF_STATUS, COL_QUALITY_CLASS, Bytes.toBytes(quality));
        put.addColumn(CF_STATUS, COL_LAST_UPDATE, Bytes.toBytes(timestampMs));

        mutator.mutate(put);
    }

    /**
     * Update capacity metrics for a tower sector.
     */
    public void updateCapacityState(String towerId, int sectorId,
                                    double prbUtil, int connectedUes,
                                    double dlThroughput, double ulThroughput,
                                    long timestampMs) throws IOException {
        byte[] rowKey = makeRowKey(towerId, sectorId);

        Put put = new Put(rowKey, timestampMs);
        put.addColumn(CF_CAPACITY, COL_PRB_UTIL, Bytes.toBytes(prbUtil));
        put.addColumn(CF_CAPACITY, COL_CONNECTED_UES, Bytes.toBytes(connectedUes));
        put.addColumn(CF_CAPACITY, COL_DL_THROUGHPUT, Bytes.toBytes(dlThroughput));
        put.addColumn(CF_CAPACITY, COL_UL_THROUGHPUT, Bytes.toBytes(ulThroughput));

        // Update capacity status
        String capStatus = classifyCapacity(prbUtil);
        put.addColumn(CF_STATUS, COL_CAPACITY_STATUS, Bytes.toBytes(capStatus));

        mutator.mutate(put);
    }

    /**
     * Set or clear the anomaly flag on a tower sector.
     */
    public void setAnomalyFlag(String towerId, int sectorId,
                               boolean isAnomaly, String reason) throws IOException {
        byte[] rowKey = makeRowKey(towerId, sectorId);

        Put put = new Put(rowKey);
        put.addColumn(CF_STATUS, COL_ANOMALY_FLAG,
                Bytes.toBytes(isAnomaly ? reason : ""));

        mutator.mutate(put);
    }

    /**
     * Get the current state of a tower sector.
     */
    public Map<String, Object> getTowerState(String towerId, int sectorId) throws IOException {
        byte[] rowKey = makeRowKey(towerId, sectorId);

        Get get = new Get(rowKey);
        get.addFamily(CF_SIGNAL);
        get.addFamily(CF_CAPACITY);
        get.addFamily(CF_STATUS);

        try (Table table = connection.getTable(TABLE_NAME)) {
            Result result = table.get(get);
            if (result.isEmpty()) {
                return null;
            }

            Map<String, Object> state = new HashMap<>();
            state.put("tower_id", towerId);
            state.put("sector_id", sectorId);

            // Signal metrics
            state.put("rsrp", getDouble(result, CF_SIGNAL, COL_RSRP));
            state.put("rsrq", getDouble(result, CF_SIGNAL, COL_RSRQ));
            state.put("sinr", getDouble(result, CF_SIGNAL, COL_SINR));
            state.put("rssi", getDouble(result, CF_SIGNAL, COL_RSSI));
            state.put("cqi", getInt(result, CF_SIGNAL, COL_CQI));

            // Capacity metrics
            state.put("prb_utilization", getDouble(result, CF_CAPACITY, COL_PRB_UTIL));
            state.put("connected_ues", getInt(result, CF_CAPACITY, COL_CONNECTED_UES));
            state.put("dl_throughput_mbps", getDouble(result, CF_CAPACITY, COL_DL_THROUGHPUT));
            state.put("ul_throughput_mbps", getDouble(result, CF_CAPACITY, COL_UL_THROUGHPUT));

            // Status
            state.put("quality_class", getString(result, CF_STATUS, COL_QUALITY_CLASS));
            state.put("capacity_status", getString(result, CF_STATUS, COL_CAPACITY_STATUS));
            state.put("last_update", getLong(result, CF_STATUS, COL_LAST_UPDATE));
            state.put("anomaly_flag", getString(result, CF_STATUS, COL_ANOMALY_FLAG));

            return state;
        }
    }

    /**
     * Get signal history for a tower sector (last N versions).
     */
    public NavigableMap<Long, Map<String, Double>> getSignalHistory(
            String towerId, int sectorId, int maxVersions) throws IOException {
        byte[] rowKey = makeRowKey(towerId, sectorId);

        Get get = new Get(rowKey);
        get.addFamily(CF_SIGNAL);
        get.readVersions(maxVersions);

        try (Table table = connection.getTable(TABLE_NAME)) {
            Result result = table.get(get);
            NavigableMap<Long, Map<String, Double>> history = new java.util.TreeMap<>();

            NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> familyMap =
                    result.getMap();

            if (familyMap != null && familyMap.containsKey(CF_SIGNAL)) {
                NavigableMap<byte[], NavigableMap<Long, byte[]>> columnMap =
                        familyMap.get(CF_SIGNAL);

                for (Map.Entry<byte[], NavigableMap<Long, byte[]>> col : columnMap.entrySet()) {
                    String colName = Bytes.toString(col.getKey());
                    for (Map.Entry<Long, byte[]> version : col.getValue().entrySet()) {
                        history.computeIfAbsent(version.getKey(), k -> new HashMap<>())
                                .put(colName, Bytes.toDouble(version.getValue()));
                    }
                }
            }

            return history;
        }
    }

    public void flush() throws IOException {
        mutator.flush();
    }

    @Override
    public void close() throws IOException {
        mutator.flush();
        mutator.close();
        connection.close();
    }

    private byte[] makeRowKey(String towerId, int sectorId) {
        return Bytes.toBytes(towerId + "#" + sectorId);
    }

    private String classifySignal(double rsrp, double sinr, int cqi) {
        if (rsrp >= -80 && sinr >= 20 && cqi >= 12) return "EXCELLENT";
        if (rsrp >= -90 && sinr >= 13 && cqi >= 7) return "GOOD";
        if (rsrp >= -100 && sinr >= 0 && cqi >= 4) return "FAIR";
        if (rsrp >= -115) return "POOR";
        return "NO_COVERAGE";
    }

    private String classifyCapacity(double prbUtil) {
        if (prbUtil > 95) return "CRITICAL";
        if (prbUtil > 80) return "WARNING";
        if (prbUtil > 60) return "MODERATE";
        return "HEALTHY";
    }

    private Double getDouble(Result r, byte[] cf, byte[] col) {
        byte[] val = r.getValue(cf, col);
        return val != null ? Bytes.toDouble(val) : null;
    }

    private Integer getInt(Result r, byte[] cf, byte[] col) {
        byte[] val = r.getValue(cf, col);
        return val != null ? Bytes.toInt(val) : null;
    }

    private Long getLong(Result r, byte[] cf, byte[] col) {
        byte[] val = r.getValue(cf, col);
        return val != null ? Bytes.toLong(val) : null;
    }

    private String getString(Result r, byte[] cf, byte[] col) {
        byte[] val = r.getValue(cf, col);
        return val != null ? Bytes.toString(val) : null;
    }
}
