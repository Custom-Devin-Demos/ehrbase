package com.telco.pipeline.mapreduce.cdr;

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
 * Driver for CDR processing and per-tower aggregation.
 *
 * Processes raw Call Detail Records from the voice switch, validates fields,
 * and aggregates per originating tower to produce daily tower-level KPIs:
 * - Call volume and duration statistics
 * - Dropped call rates and causes
 * - Signal quality metrics during calls
 * - Handoff frequency and mobility patterns
 *
 * Usage:
 *   hadoop jar telco-mapreduce.jar \
 *     com.telco.pipeline.mapreduce.cdr.CDRProcessingDriver \
 *     /data/raw/cdr/2024/01/15 \
 *     /data/processed/cdr-tower-stats/2024/01/15
 */
public class CDRProcessingDriver extends Configured implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(CDRProcessingDriver.class);

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: CDRProcessingDriver <input-path> <output-path>");
            return 1;
        }

        Configuration conf = getConf();
        Job job = Job.getInstance(conf, "Telco CDR Processing & Tower Aggregation");
        job.setJarByClass(CDRProcessingDriver.class);

        job.setMapperClass(CDRParserMapper.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);

        job.setReducerClass(CDRAggregationReducer.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(NullWritable.class);

        job.setInputFormatClass(TextInputFormat.class);
        job.setOutputFormatClass(TextOutputFormat.class);

        FileOutputFormat.setCompressOutput(job, true);
        FileOutputFormat.setOutputCompressorClass(job, SnappyCodec.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        job.setNumReduceTasks(conf.getInt("mapreduce.job.reduces", 20));

        LOG.info("Starting CDR Processing: {} -> {}", args[0], args[1]);
        boolean success = job.waitForCompletion(true);

        if (success) {
            LOG.info("CDR processing complete:");
            LOG.info("  Total CDRs: {}",
                    job.getCounters().findCounter("TelcoCDR", "TotalCDRs").getValue());
            LOG.info("  Dropped calls: {}",
                    job.getCounters().findCounter("TelcoCDR", "DroppedCalls").getValue());
            LOG.info("  Towers processed: {}",
                    job.getCounters().findCounter("TelcoCDR", "ProcessedTowers").getValue());
            LOG.info("  High drop-rate towers: {}",
                    job.getCounters().findCounter("TelcoCDR", "HighDropRateTowers").getValue());
        }

        return success ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new Configuration(), new CDRProcessingDriver(), args);
        System.exit(exitCode);
    }
}
