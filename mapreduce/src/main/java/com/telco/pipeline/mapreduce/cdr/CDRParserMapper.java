package com.telco.pipeline.mapreduce.cdr;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Mapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Parses and validates raw CDR records, emitting keyed by originating tower
 * for per-tower call statistics aggregation.
 *
 * Input: semicolon-delimited CDR records from the MSC/IMS core
 * Output key: originating_tower_id
 * Output value: parsed CDR fields (tab-delimited)
 *
 * Multi-output: emits to different named outputs based on call classification:
 * - normal: standard completed calls
 * - dropped: abnormally terminated calls
 * - emergency: emergency calls (separate processing path)
 * - quality_degraded: calls with MOS < 3.0
 */
public class CDRParserMapper extends Mapper<LongWritable, Text, Text, Text> {

    private static final Logger LOG = LoggerFactory.getLogger(CDRParserMapper.class);
    private static final int MIN_CDR_FIELDS = 20;
    private static final double MOS_DEGRADATION_THRESHOLD = 3.0;

    private Text outKey = new Text();
    private Text outValue = new Text();

    private Counter totalCdrs;
    private Counter droppedCalls;
    private Counter emergencyCalls;
    private Counter qualityDegraded;
    private Counter malformedCdrs;
    private Counter zeroDurationCalls;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        totalCdrs = context.getCounter("TelcoCDR", "TotalCDRs");
        droppedCalls = context.getCounter("TelcoCDR", "DroppedCalls");
        emergencyCalls = context.getCounter("TelcoCDR", "EmergencyCalls");
        qualityDegraded = context.getCounter("TelcoCDR", "QualityDegraded");
        malformedCdrs = context.getCounter("TelcoCDR", "MalformedCDRs");
        zeroDurationCalls = context.getCounter("TelcoCDR", "ZeroDurationCalls");
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {

        String line = value.toString().trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("HDR")) {
            return;
        }

        String[] fields = line.split(";");
        if (fields.length < MIN_CDR_FIELDS) {
            malformedCdrs.increment(1);
            return;
        }

        try {
            String cdrId = fields[0].trim();
            String callId = fields[1].trim();
            String callerHash = fields[2].trim();
            String calleeHash = fields[3].trim();
            String callType = fields[4].trim();
            long setupTimestamp = Long.parseLong(fields[5].trim());
            String connectTimestamp = fields[6].trim();
            long endTimestamp = Long.parseLong(fields[7].trim());
            int duration = Integer.parseInt(fields[8].trim());
            String termCause = fields[9].trim();
            boolean isDropped = "1".equals(fields[10].trim());
            String origTower = fields[11].trim();
            int origSector = Integer.parseInt(fields[12].trim());
            String termTower = fields[13].trim();
            int termSector = Integer.parseInt(fields[14].trim());
            int handoffCount = Integer.parseInt(fields[15].trim());
            double avgRssi = Double.parseDouble(fields[16].trim());
            double minRssi = Double.parseDouble(fields[17].trim());
            double avgSinr = Double.parseDouble(fields[18].trim());
            double mosScore = fields.length > 19 ? parseDouble(fields[19].trim(), -1) : -1;

            // Validate
            if (endTimestamp < setupTimestamp) {
                malformedCdrs.increment(1);
                return;
            }

            totalCdrs.increment(1);

            if (duration == 0) {
                zeroDurationCalls.increment(1);
            }

            if (isDropped) {
                droppedCalls.increment(1);
            }

            if (callType.contains("EMERGENCY")) {
                emergencyCalls.increment(1);
            }

            if (mosScore > 0 && mosScore < MOS_DEGRADATION_THRESHOLD) {
                qualityDegraded.increment(1);
            }

            // Key by originating tower for per-tower aggregation
            outKey.set(origTower);

            // Emit parsed and validated CDR
            StringBuilder sb = new StringBuilder();
            sb.append(cdrId).append('\t')
              .append(callId).append('\t')
              .append(callType).append('\t')
              .append(setupTimestamp).append('\t')
              .append(endTimestamp).append('\t')
              .append(duration).append('\t')
              .append(termCause).append('\t')
              .append(isDropped ? "1" : "0").append('\t')
              .append(origTower).append('\t')
              .append(origSector).append('\t')
              .append(termTower).append('\t')
              .append(termSector).append('\t')
              .append(handoffCount).append('\t')
              .append(avgRssi).append('\t')
              .append(minRssi).append('\t')
              .append(avgSinr).append('\t')
              .append(mosScore);

            outValue.set(sb.toString());
            context.write(outKey, outValue);

        } catch (NumberFormatException e) {
            malformedCdrs.increment(1);
        }
    }

    private double parseDouble(String s, double defaultVal) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
