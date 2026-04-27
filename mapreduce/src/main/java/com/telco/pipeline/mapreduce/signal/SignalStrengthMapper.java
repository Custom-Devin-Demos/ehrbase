package com.telco.pipeline.mapreduce.signal;

import com.telco.pipeline.mapreduce.common.TelcoWritables.SignalStatsWritable;
import com.telco.pipeline.mapreduce.common.TelcoWritables.TowerSectorKey;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Mapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Maps raw tower signal measurements to tower-sector-timebucket keys
 * with pre-aggregated signal statistics.
 *
 * Input: pipe-delimited signal records from HDFS raw zone
 * Output: (TowerSectorKey, SignalStatsWritable)
 *
 * Time bucketing: configurable via "signal.time.bucket.minutes" (default: 15)
 * This creates 96 buckets per day per tower-sector combination.
 */
public class SignalStrengthMapper
        extends Mapper<LongWritable, Text, TowerSectorKey, SignalStatsWritable> {

    private static final Logger LOG = LoggerFactory.getLogger(SignalStrengthMapper.class);

    private static final String BUCKET_SIZE_KEY = "signal.time.bucket.minutes";
    private static final int DEFAULT_BUCKET_MINUTES = 15;

    private long bucketSizeMs;
    private TowerSectorKey outKey;
    private SignalStatsWritable outValue;

    private Counter validRecords;
    private Counter malformedRecords;
    private Counter outOfRangeRecords;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        int bucketMinutes = context.getConfiguration().getInt(BUCKET_SIZE_KEY, DEFAULT_BUCKET_MINUTES);
        bucketSizeMs = bucketMinutes * 60L * 1000L;
        outKey = new TowerSectorKey();
        outValue = new SignalStatsWritable();

        validRecords = context.getCounter("TelcoSignal", "ValidRecords");
        malformedRecords = context.getCounter("TelcoSignal", "MalformedRecords");
        outOfRangeRecords = context.getCounter("TelcoSignal", "OutOfRangeRecords");
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {

        String line = value.toString().trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        String[] fields = line.split("\\|");
        if (fields.length < 19) {
            malformedRecords.increment(1);
            return;
        }

        try {
            String towerId = fields[0].trim();
            int sectorId = Integer.parseInt(fields[1].trim());
            long timestampMs = Long.parseLong(fields[2].trim());
            double rssi = Double.parseDouble(fields[3].trim());
            double snr = Double.parseDouble(fields[4].trim());
            double rsrp = Double.parseDouble(fields[5].trim());
            double rsrq = Double.parseDouble(fields[6].trim());
            double sinr = Double.parseDouble(fields[7].trim());
            int cqi = Integer.parseInt(fields[8].trim());
            int connectedUes = Integer.parseInt(fields[14].trim());
            double prbUtilization = Double.parseDouble(fields[15].trim());
            double dlThroughput = Double.parseDouble(fields[16].trim());
            double ulThroughput = Double.parseDouble(fields[17].trim());

            // Range validation
            if (rssi < -140 || rssi > -20) {
                outOfRangeRecords.increment(1);
                return;
            }

            // Compute time bucket
            long timeBucket = (timestampMs / bucketSizeMs) * bucketSizeMs;

            outKey = new TowerSectorKey(towerId, sectorId, timeBucket);
            outValue.reset();
            outValue.addSample(rssi, rsrp, sinr, prbUtilization,
                    dlThroughput, ulThroughput, connectedUes);

            context.write(outKey, outValue);
            validRecords.increment(1);

        } catch (NumberFormatException e) {
            malformedRecords.increment(1);
            LOG.debug("Failed to parse signal record: {}", line.substring(0, Math.min(80, line.length())));
        }
    }
}
