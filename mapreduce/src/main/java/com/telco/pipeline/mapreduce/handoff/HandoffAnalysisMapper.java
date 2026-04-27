package com.telco.pipeline.mapreduce.handoff;

import com.telco.pipeline.mapreduce.common.TelcoWritables.HandoffPairKey;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Mapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Maps handoff events to source-target tower pair keys for aggregation.
 *
 * Input: tab-delimited handoff event records
 * Format: event_id \t imsi_hash \t timestamp \t source_tower \t source_sector \t
 *         target_tower \t target_sector \t handoff_type \t cause \t result \t
 *         source_rsrp \t target_rsrp \t ue_velocity \t handoff_duration_ms \t
 *         data_interruption_ms \t is_voice_active
 *
 * Output key: (source_tower, target_tower, hour_bucket)
 * Output value: tab-delimited stats for this handoff
 */
public class HandoffAnalysisMapper
        extends Mapper<LongWritable, Text, HandoffPairKey, Text> {

    private static final Logger LOG = LoggerFactory.getLogger(HandoffAnalysisMapper.class);
    private static final long HOUR_MS = 3600_000L;

    private HandoffPairKey outKey = new HandoffPairKey();
    private Text outValue = new Text();

    private Counter totalHandoffs;
    private Counter successfulHandoffs;
    private Counter failedHandoffs;
    private Counter pingPongEvents;
    private Counter malformedRecords;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        totalHandoffs = context.getCounter("TelcoHandoff", "TotalHandoffs");
        successfulHandoffs = context.getCounter("TelcoHandoff", "SuccessfulHandoffs");
        failedHandoffs = context.getCounter("TelcoHandoff", "FailedHandoffs");
        pingPongEvents = context.getCounter("TelcoHandoff", "PingPongEvents");
        malformedRecords = context.getCounter("TelcoHandoff", "MalformedRecords");
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {

        String line = value.toString().trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        String[] fields = line.split("\t");
        if (fields.length < 16) {
            malformedRecords.increment(1);
            return;
        }

        try {
            String eventId = fields[0];
            long timestamp = Long.parseLong(fields[2]);
            String sourceTower = fields[3];
            String targetTower = fields[5];
            String handoffType = fields[7];
            String cause = fields[8];
            String result = fields[9];
            double sourceRsrp = Double.parseDouble(fields[10]);
            double targetRsrp = Double.parseDouble(fields[11]);
            String ueVelocity = fields[12];
            String handoffDuration = fields[13];
            String dataInterruption = fields[14];
            boolean isVoiceActive = "true".equalsIgnoreCase(fields[15]);

            long hourBucket = (timestamp / HOUR_MS) * HOUR_MS;

            outKey = new HandoffPairKey(sourceTower, targetTower, hourBucket);

            // Encode handoff details for the reducer
            StringBuilder sb = new StringBuilder();
            sb.append(result).append('\t')
              .append(handoffType).append('\t')
              .append(cause).append('\t')
              .append(sourceRsrp).append('\t')
              .append(targetRsrp).append('\t')
              .append(ueVelocity).append('\t')
              .append(handoffDuration).append('\t')
              .append(dataInterruption).append('\t')
              .append(isVoiceActive ? "1" : "0");

            outValue.set(sb.toString());
            context.write(outKey, outValue);

            totalHandoffs.increment(1);
            if ("SUCCESS".equals(result)) {
                successfulHandoffs.increment(1);
            } else {
                failedHandoffs.increment(1);
                if ("PING_PONG".equals(result)) {
                    pingPongEvents.increment(1);
                }
            }

        } catch (NumberFormatException e) {
            malformedRecords.increment(1);
        }
    }
}
