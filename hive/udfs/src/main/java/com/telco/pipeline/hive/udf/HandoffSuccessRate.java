package com.telco.pipeline.hive.udf;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDAF;
import org.apache.hadoop.hive.ql.exec.UDAFEvaluator;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.Text;

/**
 * User-Defined Aggregate Function that computes handoff success rate
 * with confidence interval based on sample size.
 *
 * Usage:
 *   SELECT source_tower_id, target_tower_id,
 *          handoff_success_rate(result) AS success_rate
 *   FROM handoff_events_raw
 *   GROUP BY source_tower_id, target_tower_id;
 *
 * Returns the success rate as a double between 0 and 1.
 */
@Description(
    name = "handoff_success_rate",
    value = "_FUNC_(result_string) - Computes handoff success rate from result column",
    extended = "Aggregate function: pass the handoff result column (SUCCESS/FAILURE_*).\n" +
        "Returns the ratio of SUCCESS results to total results."
)
public class HandoffSuccessRate extends UDAF {

    public static class HandoffSuccessRateEvaluator implements UDAFEvaluator {

        private int totalCount;
        private int successCount;

        public HandoffSuccessRateEvaluator() {
            init();
        }

        @Override
        public void init() {
            totalCount = 0;
            successCount = 0;
        }

        public boolean iterate(Text result) {
            if (result != null) {
                totalCount++;
                if ("SUCCESS".equals(result.toString())) {
                    successCount++;
                }
            }
            return true;
        }

        public int[] terminatePartial() {
            return new int[]{successCount, totalCount};
        }

        public boolean merge(int[] partial) {
            if (partial != null && partial.length == 2) {
                successCount += partial[0];
                totalCount += partial[1];
            }
            return true;
        }

        public DoubleWritable terminate() {
            if (totalCount == 0) {
                return null;
            }
            return new DoubleWritable((double) successCount / totalCount);
        }
    }
}
