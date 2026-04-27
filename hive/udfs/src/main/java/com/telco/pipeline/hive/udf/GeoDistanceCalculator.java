package com.telco.pipeline.hive.udf;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.DoubleWritable;

/**
 * Calculates the Haversine distance between two geographic coordinates.
 * Used for computing tower-to-tower distances in handoff analysis.
 *
 * Usage:
 *   SELECT geo_distance(src_lat, src_lon, tgt_lat, tgt_lon) AS distance_km
 *   FROM handoff_pair_analysis;
 */
@Description(
    name = "geo_distance",
    value = "_FUNC_(lat1, lon1, lat2, lon2) - Haversine distance in kilometers",
    extended = "Calculates great-circle distance between two points on Earth.\n" +
        "Arguments: latitude1, longitude1, latitude2, longitude2 (all in decimal degrees)\n" +
        "Returns: distance in kilometers (double precision)"
)
public class GeoDistanceCalculator extends UDF {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private final DoubleWritable result = new DoubleWritable();

    public DoubleWritable evaluate(DoubleWritable lat1, DoubleWritable lon1,
                                   DoubleWritable lat2, DoubleWritable lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return null;
        }

        double lat1Rad = Math.toRadians(lat1.get());
        double lat2Rad = Math.toRadians(lat2.get());
        double deltaLat = Math.toRadians(lat2.get() - lat1.get());
        double deltaLon = Math.toRadians(lon2.get() - lon1.get());

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                   Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                   Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        result.set(EARTH_RADIUS_KM * c);
        return result;
    }
}
