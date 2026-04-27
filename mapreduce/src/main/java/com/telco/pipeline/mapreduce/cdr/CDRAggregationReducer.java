package com.telco.pipeline.mapreduce.cdr;

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
 * Aggregates CDRs per originating tower to produce tower-level call statistics.
 *
 * Output: per-tower summary with call volume, dropped call rate, average quality,
 *         handoff frequency, and termination cause distribution.
 */
public class CDRAggregationReducer extends Reducer<Text, Text, Text, NullWritable> {

    private static final Logger LOG = LoggerFactory.getLogger(CDRAggregationReducer.class);
    private static final double DROPPED_CALL_ALERT_THRESHOLD = 0.02; // 2%

    private Text outKey = new Text();
    private Counter processedTowers;
    private Counter highDropRateTowers;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        processedTowers = context.getCounter("TelcoCDR", "ProcessedTowers");
        highDropRateTowers = context.getCounter("TelcoCDR", "HighDropRateTowers");
    }

    @Override
    protected void reduce(Text key, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {

        String towerId = key.toString();
        int totalCalls = 0;
        int droppedCalls = 0;
        int zeroDuration = 0;
        long totalDuration = 0;
        int totalHandoffs = 0;
        double sumRssi = 0;
        double sumSinr = 0;
        double sumMos = 0;
        int mosCount = 0;
        double minRssiOverall = Double.MAX_VALUE;

        Map<String, Integer> termCauseCounts = new HashMap<>();
        Map<String, Integer> callTypeCounts = new HashMap<>();

        for (Text value : values) {
            String[] fields = value.toString().split("\t");
            if (fields.length < 17) continue;

            try {
                String callType = fields[2];
                int duration = Integer.parseInt(fields[5]);
                String termCause = fields[6];
                boolean isDropped = "1".equals(fields[7]);
                int handoffCount = Integer.parseInt(fields[12]);
                double avgRssi = Double.parseDouble(fields[13]);
                double minRssi = Double.parseDouble(fields[14]);
                double avgSinr = Double.parseDouble(fields[15]);
                double mosScore = Double.parseDouble(fields[16]);

                totalCalls++;
                totalDuration += duration;
                totalHandoffs += handoffCount;
                sumRssi += avgRssi;
                sumSinr += avgSinr;
                minRssiOverall = Math.min(minRssiOverall, minRssi);

                if (isDropped) droppedCalls++;
                if (duration == 0) zeroDuration++;

                if (mosScore > 0) {
                    sumMos += mosScore;
                    mosCount++;
                }

                termCauseCounts.merge(termCause, 1, Integer::sum);
                callTypeCounts.merge(callType, 1, Integer::sum);

            } catch (NumberFormatException e) {
                // Skip malformed records in reducer
            }
        }

        if (totalCalls == 0) return;

        double droppedCallRate = (double) droppedCalls / totalCalls;
        double avgDuration = (double) totalDuration / totalCalls;
        double avgHandoffs = (double) totalHandoffs / totalCalls;
        double avgRssi = sumRssi / totalCalls;
        double avgSinr = sumSinr / totalCalls;
        double avgMos = mosCount > 0 ? sumMos / mosCount : -1;

        String dominantTermCause = findMax(termCauseCounts);
        String dominantCallType = findMax(callTypeCounts);

        StringBuilder sb = new StringBuilder();
        sb.append(towerId).append('\t')
          .append(totalCalls).append('\t')
          .append(droppedCalls).append('\t')
          .append(String.format("%.4f", droppedCallRate)).append('\t')
          .append(zeroDuration).append('\t')
          .append(String.format("%.1f", avgDuration)).append('\t')
          .append(String.format("%.2f", avgHandoffs)).append('\t')
          .append(String.format("%.2f", avgRssi)).append('\t')
          .append(String.format("%.2f", minRssiOverall)).append('\t')
          .append(String.format("%.2f", avgSinr)).append('\t')
          .append(String.format("%.2f", avgMos)).append('\t')
          .append(dominantTermCause).append('\t')
          .append(dominantCallType);

        outKey.set(sb.toString());
        context.write(outKey, NullWritable.get());
        processedTowers.increment(1);

        if (droppedCallRate > DROPPED_CALL_ALERT_THRESHOLD && totalCalls >= 50) {
            highDropRateTowers.increment(1);
            LOG.warn("High dropped call rate at tower {}: {:.2f}% ({}/{})",
                    towerId, droppedCallRate * 100, droppedCalls, totalCalls);
        }
    }

    private String findMax(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("UNKNOWN");
    }
}
