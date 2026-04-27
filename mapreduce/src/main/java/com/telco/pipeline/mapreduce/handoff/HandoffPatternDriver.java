package com.telco.pipeline.mapreduce.handoff;

import com.telco.pipeline.mapreduce.common.TelcoWritables.HandoffPairKey;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
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
 * Driver for handoff pattern analysis MapReduce job.
 *
 * Analyzes handoff events between cell tower pairs to identify:
 * - High-failure handoff corridors requiring parameter tuning
 * - Ping-pong handoff patterns indicating coverage overlap issues
 * - Voice call disruption during handoffs
 * - Handoff timing optimization opportunities
 *
 * Usage:
 *   hadoop jar telco-mapreduce.jar \
 *     com.telco.pipeline.mapreduce.handoff.HandoffPatternDriver \
 *     /data/raw/handoff-events/2024/01/15 \
 *     /data/processed/handoff-analysis/2024/01/15
 */
public class HandoffPatternDriver extends Configured implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(HandoffPatternDriver.class);

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: HandoffPatternDriver <input-path> <output-path>");
            return 1;
        }

        Configuration conf = getConf();
        Job job = Job.getInstance(conf, "Telco Handoff Pattern Analysis");
        job.setJarByClass(HandoffPatternDriver.class);

        job.setMapperClass(HandoffAnalysisMapper.class);
        job.setMapOutputKeyClass(HandoffPairKey.class);
        job.setMapOutputValueClass(Text.class);

        job.setReducerClass(HandoffAnalysisReducer.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(NullWritable.class);

        job.setInputFormatClass(TextInputFormat.class);
        job.setOutputFormatClass(TextOutputFormat.class);

        FileOutputFormat.setCompressOutput(job, true);
        FileOutputFormat.setOutputCompressorClass(job, SnappyCodec.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        // Set reducers based on expected data volume
        job.setNumReduceTasks(conf.getInt("mapreduce.job.reduces", 30));

        LOG.info("Starting Handoff Pattern Analysis: {} -> {}", args[0], args[1]);
        boolean success = job.waitForCompletion(true);

        if (success) {
            long totalHandoffs = job.getCounters()
                    .findCounter("TelcoHandoff", "TotalHandoffs").getValue();
            long failedHandoffs = job.getCounters()
                    .findCounter("TelcoHandoff", "FailedHandoffs").getValue();
            long highFailure = job.getCounters()
                    .findCounter("TelcoHandoff", "HighFailureRatePairs").getValue();

            LOG.info("Handoff analysis complete:");
            LOG.info("  Total handoffs: {}", totalHandoffs);
            LOG.info("  Failed handoffs: {} ({:.2f}%)",
                    failedHandoffs,
                    totalHandoffs > 0 ? (double) failedHandoffs / totalHandoffs * 100 : 0);
            LOG.info("  High-failure tower pairs: {}", highFailure);
        }

        return success ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new Configuration(), new HandoffPatternDriver(), args);
        System.exit(exitCode);
    }
}
