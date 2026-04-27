-- ============================================================================
-- Network Coverage Gap Analysis
-- ============================================================================
-- Identifies areas with poor/no coverage by analyzing the spatial
-- distribution of signal quality measurements.
-- ============================================================================

SET hive.vectorized.execution.enabled=true;

ADD JAR /opt/telco/lib/telco-hive-udfs.jar;
CREATE TEMPORARY FUNCTION classify_signal AS 'com.telco.pipeline.hive.udf.SignalStrengthClassifier';
CREATE TEMPORARY FUNCTION geo_distance AS 'com.telco.pipeline.hive.udf.GeoDistanceCalculator';

-- Towers with persistent poor coverage
SELECT
    sq.tower_id,
    tm.site_name,
    tm.region,
    tm.market,
    tm.morphology,
    tm.tower_type,
    tm.latitude,
    tm.longitude,
    COUNT(*) AS measurement_periods,
    SUM(CASE WHEN classify_signal(sq.avg_rsrp) IN ('POOR', 'NO_COVERAGE') THEN 1 ELSE 0 END) AS poor_periods,
    ROUND(
        SUM(CASE WHEN classify_signal(sq.avg_rsrp) IN ('POOR', 'NO_COVERAGE') THEN 1 ELSE 0 END) * 100.0 / COUNT(*),
        2
    ) AS poor_coverage_pct,
    ROUND(MIN(sq.min_rsrp), 2) AS worst_rsrp,
    ROUND(AVG(sq.avg_rsrp), 2) AS avg_rsrp,
    ROUND(AVG(sq.avg_sinr), 2) AS avg_sinr,
    ROUND(AVG(sq.avg_prb_utilization), 2) AS avg_prb_util,
    -- Identify if this is a capacity or coverage issue
    CASE
        WHEN AVG(sq.avg_prb_utilization) > 80 AND AVG(sq.avg_rsrp) > -100
            THEN 'CAPACITY_CONSTRAINED'
        WHEN AVG(sq.avg_rsrp) < -110
            THEN 'COVERAGE_HOLE'
        WHEN AVG(sq.avg_sinr) < 0 AND AVG(sq.avg_rsrp) > -100
            THEN 'INTERFERENCE_DOMINATED'
        ELSE 'MIXED_ISSUE'
    END AS issue_classification,
    -- Nearest neighbor tower distance
    (
        SELECT MIN(geo_distance(tm.latitude, tm.longitude, n.latitude, n.longitude))
        FROM telco_raw.tower_metadata n
        WHERE n.tower_id != tm.tower_id AND n.is_active = true
    ) AS nearest_tower_km
FROM telco_processed.signal_quality_agg sq
JOIN telco_raw.tower_metadata tm ON sq.tower_id = tm.tower_id
WHERE sq.dt BETWEEN DATE_SUB('${hivevar:report_date}', 7) AND '${hivevar:report_date}'
  AND tm.is_active = true
GROUP BY sq.tower_id, tm.site_name, tm.region, tm.market,
         tm.morphology, tm.tower_type, tm.latitude, tm.longitude
HAVING poor_coverage_pct > 20
ORDER BY poor_coverage_pct DESC;
