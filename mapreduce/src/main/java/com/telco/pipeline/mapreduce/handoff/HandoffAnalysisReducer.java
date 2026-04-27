package com.telco.pipeline.mapreduce.handoff;

import com.telco.pipeline.mapreduce.common.TelcoWritables.HandoffPairKey;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Reducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Aggregates handoff events per source-target tower pair per hour.
 *
 * Computes:
 * - Total handoff attempts and success/failure breakdown
 * - Per-type and per-cause distributions
 * - Average handoff duration and data interruption time
 * - RSRP differential statistics
 * - Voice call impact (handoffs during active voice calls)
 * - Ping-pong detection rate
 *
 * Output format (tab-delimited):
 *   source_tower \t target_tower \t hour_bucket \t total_attempts \t success_count \t
 *   failure_count \t success_rate \t avg_handoff_duration_ms \t avg_data_interruption_ms \t
 *   avg_rsrp_diff \t voice_handoff_count \t ping_pong_count \t dominant_type \t dominant_cause
 */
public class HandoffAnalysisReducer
        extends Reducer<HandoffPairKey, Text, Text, NullWritable> {

    private static final Logger LOG = LoggerFactory.getLogger(HandoffAnalysisReducer.class);
    private static final double CRITICAL_FAILURE_RATE = 0.15;

    private Text outKey = new Text();
    private Counter highFailureRatePairs;
    private Counter processedPairs;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        highFailureRatePairs = context.getCounter("TelcoHandoff", "HighFailureRatePairs");
        processedPairs = context.getCounter("TelcoHandoff", "ProcessedPairs");
    }

    @Override
    protected void reduce(HandoffPairKey key, Iterable<Text> values,
                          Context context) throws IOException, InterruptedException {

        int totalAttempts = 0;
        int successCount = 0;
        int failureCount = 0;
        int voiceHandoffCount = 0;
        int pingPongCount = 0;

        double sumHandoffDuration = 0;
        double sumDataInterruption = 0;
        double sumRsrpDiff = 0;
        int durationSampleCount = 0;

        Map<String, Integer> typeCounts = new HashMap<>();
        Map<String, Integer> causeCounts = new HashMap<>();

        for (Text value : values) {
            String[] fields = value.toString().split("\t");
            if (fields.length < 9) continue;

            String result = fields[0];
            String handoffType = fields[1];
            String cause = fields[2];
            double sourceRsrp = parseDouble(fields[3], 0);
            double targetRsrp = parseDouble(fields[4], 0);
            String ueVelocity = fields[5];
            double handoffDuration = parseDouble(fields[6], -1);
            double dataInterruption = parseDouble(fields[7], -1);
            boolean isVoiceActive = "1".equals(fields[8]);

            totalAttempts++;
            typeCounts.merge(handoffType, 1, Integer::sum);
            causeCounts.merge(cause, 1, Integer::sum);

            if ("SUCCESS".equals(result)) {
                successCount++;
            } else {
                failureCount++;
                if ("PING_PONG".equals(result)) {
                    pingPongCount++;
                }
            }

            if (isVoiceActive) {
                voiceHandoffCount++;
            }

            sumRsrpDiff += (targetRsrp - sourceRsrp);

            if (handoffDuration >= 0) {
                sumHandoffDuration += handoffDuration;
                sumDataInterruption += Math.max(0, dataInterruption);
                durationSampleCount++;
            }
        }

        if (totalAttempts == 0) return;

        double successRate = (double) successCount / totalAttempts;
        double avgHandoffDuration = durationSampleCount > 0
                ? sumHandoffDuration / durationSampleCount : 0;
        double avgDataInterruption = durationSampleCount > 0
                ? sumDataInterruption / durationSampleCount : 0;
        double avgRsrpDiff = sumRsrpDiff / totalAttempts;

        // Find dominant type and cause
        String dominantType = findMax(typeCounts);
        String dominantCause = findMax(causeCounts);

        StringBuilder sb = new StringBuilder();
        sb.append(key.getSourceTowerId()).append('\t')
          .append(key.getTargetTowerId()).append('\t')
          .append(key.getHourBucket()).append('\t')
          .append(totalAttempts).append('\t')
          .append(successCount).append('\t')
          .append(failureCount).append('\t')
          .append(String.format("%.4f", successRate)).append('\t')
          .append(String.format("%.1f", avgHandoffDuration)).append('\t')
          .append(String.format("%.1f", avgDataInterruption)).append('\t')
          .append(String.format("%.2f", avgRsrpDiff)).append('\t')
          .append(voiceHandoffCount).append('\t')
          .append(pingPongCount).append('\t')
          .append(dominantType).append('\t')
          .append(dominantCause);

        outKey.set(sb.toString());
        context.write(outKey, NullWritable.get());
        processedPairs.increment(1);

        if ((1.0 - successRate) > CRITICAL_FAILURE_RATE && totalAttempts >= 10) {
            highFailureRatePairs.increment(1);
            LOG.warn("High handoff failure rate: {} -> {}, rate={:.2f}%, attempts={}",
                    key.getSourceTowerId(), key.getTargetTowerId(),
                    (1.0 - successRate) * 100, totalAttempts);
        }
    }

    private String findMax(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("UNKNOWN");
    }

    private double parseDouble(String s, double defaultVal) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
