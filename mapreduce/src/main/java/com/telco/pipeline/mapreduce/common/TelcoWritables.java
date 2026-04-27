package com.telco.pipeline.mapreduce.common;

import org.apache.hadoop.io.WritableComparable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/**
 * Custom Hadoop Writable types for the telco pipeline.
 */
public class TelcoWritables {

    /**
     * Composite key for tower-sector signal aggregations.
     * Sorts by tower ID, then sector, then time bucket.
     */
    public static class TowerSectorKey implements WritableComparable<TowerSectorKey> {

        private String towerId;
        private int sectorId;
        private long timeBucket;

        public TowerSectorKey() {
            this.towerId = "";
            this.sectorId = 0;
            this.timeBucket = 0;
        }

        public TowerSectorKey(String towerId, int sectorId, long timeBucket) {
            this.towerId = towerId;
            this.sectorId = sectorId;
            this.timeBucket = timeBucket;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            out.writeUTF(towerId);
            out.writeInt(sectorId);
            out.writeLong(timeBucket);
        }

        @Override
        public void readFields(DataInput in) throws IOException {
            towerId = in.readUTF();
            sectorId = in.readInt();
            timeBucket = in.readLong();
        }

        @Override
        public int compareTo(TowerSectorKey other) {
            int cmp = towerId.compareTo(other.towerId);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(sectorId, other.sectorId);
            if (cmp != 0) return cmp;
            return Long.compare(timeBucket, other.timeBucket);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TowerSectorKey)) return false;
            TowerSectorKey that = (TowerSectorKey) o;
            return sectorId == that.sectorId &&
                    timeBucket == that.timeBucket &&
                    Objects.equals(towerId, that.towerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(towerId, sectorId, timeBucket);
        }

        @Override
        public String toString() {
            return towerId + ":" + sectorId + "@" + timeBucket;
        }

        public String getTowerId() { return towerId; }
        public int getSectorId() { return sectorId; }
        public long getTimeBucket() { return timeBucket; }
    }

    /**
     * Aggregated signal statistics writable.
     */
    public static class SignalStatsWritable implements WritableComparable<SignalStatsWritable> {

        private double sumRssi;
        private double sumRsrp;
        private double sumSinr;
        private double minRssi;
        private double maxRssi;
        private double minRsrp;
        private double maxRsrp;
        private double sumPrbUtilization;
        private double sumDlThroughput;
        private double sumUlThroughput;
        private long totalConnectedUes;
        private int sampleCount;
        private int poorQualityCount;

        public SignalStatsWritable() {
            reset();
        }

        public void reset() {
            sumRssi = 0;
            sumRsrp = 0;
            sumSinr = 0;
            minRssi = Double.MAX_VALUE;
            maxRssi = Double.MIN_VALUE;
            minRsrp = Double.MAX_VALUE;
            maxRsrp = Double.MIN_VALUE;
            sumPrbUtilization = 0;
            sumDlThroughput = 0;
            sumUlThroughput = 0;
            totalConnectedUes = 0;
            sampleCount = 0;
            poorQualityCount = 0;
        }

        public void addSample(double rssi, double rsrp, double sinr,
                              double prbUtil, double dlThroughput, double ulThroughput,
                              int connectedUes) {
            sumRssi += rssi;
            sumRsrp += rsrp;
            sumSinr += sinr;
            minRssi = Math.min(minRssi, rssi);
            maxRssi = Math.max(maxRssi, rssi);
            minRsrp = Math.min(minRsrp, rsrp);
            maxRsrp = Math.max(maxRsrp, rsrp);
            sumPrbUtilization += prbUtil;
            sumDlThroughput += dlThroughput;
            sumUlThroughput += ulThroughput;
            totalConnectedUes += connectedUes;
            sampleCount++;
            if (rsrp < -110 || sinr < 0) {
                poorQualityCount++;
            }
        }

        public void merge(SignalStatsWritable other) {
            sumRssi += other.sumRssi;
            sumRsrp += other.sumRsrp;
            sumSinr += other.sumSinr;
            minRssi = Math.min(minRssi, other.minRssi);
            maxRssi = Math.max(maxRssi, other.maxRssi);
            minRsrp = Math.min(minRsrp, other.minRsrp);
            maxRsrp = Math.max(maxRsrp, other.maxRsrp);
            sumPrbUtilization += other.sumPrbUtilization;
            sumDlThroughput += other.sumDlThroughput;
            sumUlThroughput += other.sumUlThroughput;
            totalConnectedUes += other.totalConnectedUes;
            sampleCount += other.sampleCount;
            poorQualityCount += other.poorQualityCount;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            out.writeDouble(sumRssi);
            out.writeDouble(sumRsrp);
            out.writeDouble(sumSinr);
            out.writeDouble(minRssi);
            out.writeDouble(maxRssi);
            out.writeDouble(minRsrp);
            out.writeDouble(maxRsrp);
            out.writeDouble(sumPrbUtilization);
            out.writeDouble(sumDlThroughput);
            out.writeDouble(sumUlThroughput);
            out.writeLong(totalConnectedUes);
            out.writeInt(sampleCount);
            out.writeInt(poorQualityCount);
        }

        @Override
        public void readFields(DataInput in) throws IOException {
            sumRssi = in.readDouble();
            sumRsrp = in.readDouble();
            sumSinr = in.readDouble();
            minRssi = in.readDouble();
            maxRssi = in.readDouble();
            minRsrp = in.readDouble();
            maxRsrp = in.readDouble();
            sumPrbUtilization = in.readDouble();
            sumDlThroughput = in.readDouble();
            sumUlThroughput = in.readDouble();
            totalConnectedUes = in.readLong();
            sampleCount = in.readInt();
            poorQualityCount = in.readInt();
        }

        @Override
        public int compareTo(SignalStatsWritable other) {
            return Integer.compare(sampleCount, other.sampleCount);
        }

        public double getAvgRssi() { return sampleCount > 0 ? sumRssi / sampleCount : 0; }
        public double getAvgRsrp() { return sampleCount > 0 ? sumRsrp / sampleCount : 0; }
        public double getAvgSinr() { return sampleCount > 0 ? sumSinr / sampleCount : 0; }
        public double getAvgPrbUtilization() { return sampleCount > 0 ? sumPrbUtilization / sampleCount : 0; }
        public double getAvgDlThroughput() { return sampleCount > 0 ? sumDlThroughput / sampleCount : 0; }
        public double getAvgUlThroughput() { return sampleCount > 0 ? sumUlThroughput / sampleCount : 0; }
        public double getAvgConnectedUes() { return sampleCount > 0 ? (double) totalConnectedUes / sampleCount : 0; }
        public double getPoorQualityRatio() { return sampleCount > 0 ? (double) poorQualityCount / sampleCount : 0; }
        public double getMinRssi() { return minRssi; }
        public double getMaxRssi() { return maxRssi; }
        public double getMinRsrp() { return minRsrp; }
        public double getMaxRsrp() { return maxRsrp; }
        public int getSampleCount() { return sampleCount; }
    }

    /**
     * Composite key for handoff pair analysis.
     * Groups handoffs by source-target tower pair.
     */
    public static class HandoffPairKey implements WritableComparable<HandoffPairKey> {

        private String sourceTowerId;
        private String targetTowerId;
        private long hourBucket;

        public HandoffPairKey() {
            this.sourceTowerId = "";
            this.targetTowerId = "";
            this.hourBucket = 0;
        }

        public HandoffPairKey(String sourceTowerId, String targetTowerId, long hourBucket) {
            this.sourceTowerId = sourceTowerId;
            this.targetTowerId = targetTowerId;
            this.hourBucket = hourBucket;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            out.writeUTF(sourceTowerId);
            out.writeUTF(targetTowerId);
            out.writeLong(hourBucket);
        }

        @Override
        public void readFields(DataInput in) throws IOException {
            sourceTowerId = in.readUTF();
            targetTowerId = in.readUTF();
            hourBucket = in.readLong();
        }

        @Override
        public int compareTo(HandoffPairKey other) {
            int cmp = sourceTowerId.compareTo(other.sourceTowerId);
            if (cmp != 0) return cmp;
            cmp = targetTowerId.compareTo(other.targetTowerId);
            if (cmp != 0) return cmp;
            return Long.compare(hourBucket, other.hourBucket);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HandoffPairKey)) return false;
            HandoffPairKey that = (HandoffPairKey) o;
            return hourBucket == that.hourBucket &&
                    Objects.equals(sourceTowerId, that.sourceTowerId) &&
                    Objects.equals(targetTowerId, that.targetTowerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceTowerId, targetTowerId, hourBucket);
        }

        public String getSourceTowerId() { return sourceTowerId; }
        public String getTargetTowerId() { return targetTowerId; }
        public long getHourBucket() { return hourBucket; }
    }
}
