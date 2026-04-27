package com.telco.pipeline.hive.udf;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.Text;

/**
 * Classifies signal strength measurements into quality tiers based on
 * 3GPP TS 36.133 reference values and operator-specific thresholds.
 *
 * Usage:
 *   SELECT classify_signal(rsrp, sinr, cqi) FROM tower_signals;
 *
 * Returns: EXCELLENT | GOOD | FAIR | POOR | NO_COVERAGE
 */
@Description(
    name = "classify_signal",
    value = "_FUNC_(rsrp_dbm, sinr_db, cqi) - Classifies signal quality into tiers",
    extended = "Returns signal quality classification based on RSRP, SINR, and CQI.\n" +
        "EXCELLENT: RSRP >= -80, SINR >= 20, CQI >= 12\n" +
        "GOOD: RSRP >= -90, SINR >= 13, CQI >= 7\n" +
        "FAIR: RSRP >= -100, SINR >= 0, CQI >= 4\n" +
        "POOR: RSRP >= -115, any SINR/CQI\n" +
        "NO_COVERAGE: RSRP < -115"
)
public class SignalStrengthClassifier extends UDF {

    private final Text result = new Text();

    public Text evaluate(DoubleWritable rsrpDbm, DoubleWritable sinrDb,
                         org.apache.hadoop.io.IntWritable cqi) {
        if (rsrpDbm == null) {
            result.set("UNKNOWN");
            return result;
        }

        double rsrp = rsrpDbm.get();
        double sinr = sinrDb != null ? sinrDb.get() : Double.MIN_VALUE;
        int cqiVal = cqi != null ? cqi.get() : 0;

        if (rsrp >= -80 && sinr >= 20 && cqiVal >= 12) {
            result.set("EXCELLENT");
        } else if (rsrp >= -90 && sinr >= 13 && cqiVal >= 7) {
            result.set("GOOD");
        } else if (rsrp >= -100 && sinr >= 0 && cqiVal >= 4) {
            result.set("FAIR");
        } else if (rsrp >= -115) {
            result.set("POOR");
        } else {
            result.set("NO_COVERAGE");
        }

        return result;
    }

    /**
     * Overloaded for RSRP-only classification when SINR/CQI unavailable.
     */
    public Text evaluate(DoubleWritable rsrpDbm) {
        if (rsrpDbm == null) {
            result.set("UNKNOWN");
            return result;
        }

        double rsrp = rsrpDbm.get();
        if (rsrp >= -80) result.set("EXCELLENT");
        else if (rsrp >= -90) result.set("GOOD");
        else if (rsrp >= -100) result.set("FAIR");
        else if (rsrp >= -115) result.set("POOR");
        else result.set("NO_COVERAGE");

        return result;
    }
}
