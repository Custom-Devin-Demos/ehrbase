package com.telco.pipeline.mapreduce.signal;

import com.telco.pipeline.mapreduce.common.TelcoPartitioner;
import com.telco.pipeline.mapreduce.common.TelcoWritables.SignalStatsWritable;
import com.telco.pipeline.mapreduce.common.TelcoWritables.TowerSectorKey;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.SnappyCodec;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driver for the signal quality aggregation MapReduce job.
 *
 * Usage:
 *   hadoop jar telco-mapreduce.jar com.telco.pipeline.mapreduce.signal.SignalQualityDriver \
 *     -Dsignal.time.bucket.minutes=15 \
 *     /data/raw/tower-signals/2024/01/15 \
 *     /data/processed/signal-quality/2024/01/15
 *
 * Uses a combiner for local pre-aggregation to reduce shuffle volume
 * (typically 10-20x reduction in data transferred).
 */
public class SignalQualityDriver extends Configured implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(SignalQualityDriver.class);

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SignalQualityDriver <input-path> <output-path>");
            System.err.println("  Optional: -Dsignal.time.bucket.minutes=15");
            System.err.println("  Optional: -Dmapreduce.job.reduces=<num_reducers>");
            return 1;
        }

        Configuration conf = getConf();
        Job job = Job.getInstance(conf, "Telco Signal Quality Aggregation");
        job.setJarByClass(SignalQualityDriver.class);

        // Mapper
        job.setMapperClass(SignalStrengthMapper.class);
        job.setMapOutputKeyClass(TowerSectorKey.class);
        job.setMapOutputValueClass(SignalStatsWritable.class);

        // Combiner (same logic as reducer for pre-aggregation)
        job.setCombinerClass(SignalStrengthCombiner.class);

        // Reducer
        job.setReducerClass(SignalStrengthReducer.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        // Custom partitioner
        job.setPartitionerClass(TelcoPartitioner.class);

        // I/O formats
        job.setInputFormatClass(TextInputFormat.class);
        job.setOutputFormatClass(TextOutputFormat.class);

        // Compression
        FileOutputFormat.setCompressOutput(job, true);
        FileOutputFormat.setOutputCompressorClass(job, SnappyCodec.class);

        // Paths
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        // Performance tuning
        if (conf.get("mapreduce.job.reduces") == null) {
            job.setNumReduceTasks(calculateReducers(conf));
        }

        // Speculative execution off for consistency
        conf.setBoolean("mapreduce.map.speculative", false);
        conf.setBoolean("mapreduce.reduce.speculative", false);

        LOG.info("Starting Signal Quality Aggregation job");
        LOG.info("  Input: {}", args[0]);
        LOG.info("  Output: {}", args[1]);
        LOG.info("  Bucket size: {} minutes",
                conf.getInt("signal.time.bucket.minutes", 15));

        boolean success = job.waitForCompletion(true);

        if (success) {
            LOG.info("Job completed successfully");
            LOG.info("  Valid records: {}",
                    job.getCounters().findCounter("TelcoSignal", "ValidRecords").getValue());
            LOG.info("  Malformed records: {}",
                    job.getCounters().findCounter("TelcoSignal", "MalformedRecords").getValue());
            LOG.info("  Aggregated buckets: {}",
                    job.getCounters().findCounter("TelcoSignal", "AggregatedBuckets").getValue());
        }

        return success ? 0 : 1;
    }

    private int calculateReducers(Configuration conf) {
        // Heuristic: one reducer per ~256MB of input
        return Math.max(1, Math.min(200, 50));
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new Configuration(), new SignalQualityDriver(), args);
        System.exit(exitCode);
    }
}
