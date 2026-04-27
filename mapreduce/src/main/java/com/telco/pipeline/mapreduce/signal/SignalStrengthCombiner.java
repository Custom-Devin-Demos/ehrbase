package com.telco.pipeline.mapreduce.signal;

import com.telco.pipeline.mapreduce.common.TelcoWritables.SignalStatsWritable;
import com.telco.pipeline.mapreduce.common.TelcoWritables.TowerSectorKey;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;

/**
 * Combiner for local pre-aggregation of signal statistics.
 * Merges map outputs before shuffle to reduce network I/O.
 *
 * Since SignalStatsWritable is associative and commutative,
 * the combiner can safely pre-aggregate partial results.
 */
public class SignalStrengthCombiner
        extends Reducer<TowerSectorKey, SignalStatsWritable,
                         TowerSectorKey, SignalStatsWritable> {

    private SignalStatsWritable combined = new SignalStatsWritable();

    @Override
    protected void reduce(TowerSectorKey key, Iterable<SignalStatsWritable> values,
                          Context context) throws IOException, InterruptedException {
        combined.reset();
        for (SignalStatsWritable value : values) {
            combined.merge(value);
        }
        context.write(key, combined);
    }
}
