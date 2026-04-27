package com.telco.pipeline.mapreduce.signal;

import com.telco.pipeline.mapreduce.common.TelcoWritables.SignalStatsWritable;
import com.telco.pipeline.mapreduce.common.TelcoWritables.TowerSectorKey;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Reducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Reduces per-tower-sector-timebucket signal samples into aggregate statistics.
 *
 * Output format (tab-delimited):
 *   tower_id \t sector_id \t time_bucket \t sample_count \t avg_rssi \t avg_rsrp \t
 *   avg_sinr \t min_rssi \t max_rssi \t min_rsrp \t max_rsrp \t avg_prb_util \t
 *   avg_dl_throughput \t avg_ul_throughput \t avg_connected_ues \t poor_quality_ratio
 *
 * A combiner-compatible design: the reducer can serve as both combiner and
 * final reducer since SignalStatsWritable supports incremental merging.
 */
public class SignalStrengthReducer
        extends Reducer<TowerSectorKey, SignalStatsWritable, Text, Text> {

    private static final Logger LOG = LoggerFactory.getLogger(SignalStrengthReducer.class);
    private static final double POOR_QUALITY_ALERT_THRESHOLD = 0.3;

    private Text outKey = new Text();
    private Text outValue = new Text();
    private Counter aggregatedBuckets;
    private Counter poorQualityAlerts;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        aggregatedBuckets = context.getCounter("TelcoSignal", "AggregatedBuckets");
        poorQualityAlerts = context.getCounter("TelcoSignal", "PoorQualityAlerts");
    }

    @Override
    protected void reduce(TowerSectorKey key, Iterable<SignalStatsWritable> values,
                          Context context) throws IOException, InterruptedException {

        SignalStatsWritable merged = new SignalStatsWritable();
        for (SignalStatsWritable value : values) {
            merged.merge(value);
        }

        if (merged.getSampleCount() == 0) {
            return;
        }

        outKey.set(String.format("%s\t%d\t%d",
                key.getTowerId(), key.getSectorId(), key.getTimeBucket()));

        outValue.set(String.format(
                "%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.1f\t%.4f",
                merged.getSampleCount(),
                merged.getAvgRssi(),
                merged.getAvgRsrp(),
                merged.getAvgSinr(),
                merged.getMinRssi(),
                merged.getMaxRssi(),
                merged.getMinRsrp(),
                merged.getMaxRsrp(),
                merged.getAvgPrbUtilization(),
                merged.getAvgDlThroughput(),
                merged.getAvgUlThroughput(),
                merged.getAvgConnectedUes(),
                merged.getPoorQualityRatio()));

        context.write(outKey, outValue);
        aggregatedBuckets.increment(1);

        if (merged.getPoorQualityRatio() > POOR_QUALITY_ALERT_THRESHOLD) {
            poorQualityAlerts.increment(1);
            LOG.warn("Poor signal quality detected: tower={}, sector={}, bucket={}, ratio={:.2f}",
                    key.getTowerId(), key.getSectorId(), key.getTimeBucket(),
                    merged.getPoorQualityRatio());
        }
    }
}
