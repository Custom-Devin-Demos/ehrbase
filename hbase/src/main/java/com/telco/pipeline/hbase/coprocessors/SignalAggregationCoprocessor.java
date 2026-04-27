package com.telco.pipeline.hbase.coprocessors;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessor;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * HBase region coprocessor for server-side signal aggregation.
 *
 * Computes running statistics (mean, min, max, stddev) on signal
 * measurements directly on the region server, avoiding the need to
 * transfer raw data to the client for aggregation.
 *
 * This is used by the NOC dashboard for fast tower state queries
 * where computing aggregates client-side would be too slow.
 *
 * Endpoint protocol:
 * - getAggregatedSignalStats(towerId, sectorId, startTime, endTime)
 *   Returns: { avgRsrp, minRsrp, maxRsrp, stddevRsrp, avgSinr, ... }
 */
public class SignalAggregationCoprocessor implements RegionCoprocessor, RegionObserver {

    private static final Logger LOG = LoggerFactory.getLogger(SignalAggregationCoprocessor.class);

    private static final byte[] CF_SIGNAL = Bytes.toBytes("signal");
    private static final byte[] COL_RSRP = Bytes.toBytes("rsrp");
    private static final byte[] COL_SINR = Bytes.toBytes("sinr");
    private static final byte[] COL_RSSI = Bytes.toBytes("rssi");

    @Override
    public Optional<RegionObserver> getRegionObserver() {
        return Optional.of(this);
    }

    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
        LOG.info("SignalAggregationCoprocessor started on region: {}",
                ((RegionCoprocessorEnvironment) env).getRegionInfo().getRegionNameAsString());
    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {
        LOG.info("SignalAggregationCoprocessor stopped");
    }

    /**
     * Server-side aggregation of signal measurements for a tower sector.
     *
     * @param towerId  tower identifier
     * @param sectorId sector index
     * @param maxVersions maximum number of historical versions to aggregate
     * @return aggregated signal statistics
     */
    public SignalStats aggregateSignalStats(
            RegionCoprocessorEnvironment env,
            String towerId, int sectorId, int maxVersions) throws IOException {

        byte[] rowKey = Bytes.toBytes(towerId + "#" + sectorId);

        Get get = new Get(rowKey);
        get.addFamily(CF_SIGNAL);
        get.readVersions(maxVersions);

        Result result = env.getRegion().get(get);
        if (result.isEmpty()) {
            return SignalStats.empty();
        }

        SignalStats stats = new SignalStats();

        // Aggregate RSRP across versions
        NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> map = result.getMap();
        if (map != null && map.containsKey(CF_SIGNAL)) {
            NavigableMap<byte[], NavigableMap<Long, byte[]>> columns = map.get(CF_SIGNAL);

            // Aggregate RSRP
            if (columns.containsKey(COL_RSRP)) {
                for (byte[] val : columns.get(COL_RSRP).values()) {
                    double rsrp = Bytes.toDouble(val);
                    stats.addRsrpSample(rsrp);
                }
            }

            // Aggregate SINR
            if (columns.containsKey(COL_SINR)) {
                for (byte[] val : columns.get(COL_SINR).values()) {
                    double sinr = Bytes.toDouble(val);
                    stats.addSinrSample(sinr);
                }
            }

            // Aggregate RSSI
            if (columns.containsKey(COL_RSSI)) {
                for (byte[] val : columns.get(COL_RSSI).values()) {
                    double rssi = Bytes.toDouble(val);
                    stats.addRssiSample(rssi);
                }
            }
        }

        return stats;
    }

    /**
     * Aggregated signal statistics container.
     */
    public static class SignalStats {
        private int count;
        private double sumRsrp, sumSqRsrp, minRsrp, maxRsrp;
        private double sumSinr, sumSqSinr, minSinr, maxSinr;
        private double sumRssi, sumSqRssi, minRssi, maxRssi;

        public SignalStats() {
            this.count = 0;
            this.minRsrp = Double.MAX_VALUE;
            this.maxRsrp = Double.MIN_VALUE;
            this.minSinr = Double.MAX_VALUE;
            this.maxSinr = Double.MIN_VALUE;
            this.minRssi = Double.MAX_VALUE;
            this.maxRssi = Double.MIN_VALUE;
        }

        public static SignalStats empty() {
            return new SignalStats();
        }

        public void addRsrpSample(double rsrp) {
            count++;
            sumRsrp += rsrp;
            sumSqRsrp += rsrp * rsrp;
            minRsrp = Math.min(minRsrp, rsrp);
            maxRsrp = Math.max(maxRsrp, rsrp);
        }

        public void addSinrSample(double sinr) {
            sumSinr += sinr;
            sumSqSinr += sinr * sinr;
            minSinr = Math.min(minSinr, sinr);
            maxSinr = Math.max(maxSinr, sinr);
        }

        public void addRssiSample(double rssi) {
            sumRssi += rssi;
            sumSqRssi += rssi * rssi;
            minRssi = Math.min(minRssi, rssi);
            maxRssi = Math.max(maxRssi, rssi);
        }

        public int getCount() { return count; }
        public double getAvgRsrp() { return count > 0 ? sumRsrp / count : 0; }
        public double getAvgSinr() { return count > 0 ? sumSinr / count : 0; }
        public double getAvgRssi() { return count > 0 ? sumRssi / count : 0; }
        public double getMinRsrp() { return minRsrp; }
        public double getMaxRsrp() { return maxRsrp; }

        public double getStddevRsrp() {
            if (count <= 1) return 0;
            double variance = (sumSqRsrp - (sumRsrp * sumRsrp / count)) / (count - 1);
            return Math.sqrt(Math.max(0, variance));
        }
    }
}
