-- ============================================================================
-- Hourly Signal Quality Dashboard Query
-- ============================================================================
-- Powers the real-time network operations center (NOC) dashboard.
-- Refreshed every hour via Oozie coordinator.
-- ============================================================================

SET hive.vectorized.execution.enabled=true;

ADD JAR /opt/telco/lib/telco-hive-udfs.jar;
CREATE TEMPORARY FUNCTION classify_signal AS 'com.telco.pipeline.hive.udf.SignalStrengthClassifier';

SELECT
    sq.tower_id,
    tm.site_name,
    tm.region,
    tm.market,
    tm.tower_type,
    tm.morphology,
    sq.sector_id,
    sq.time_bucket,
    sq.sample_count,
    ROUND(sq.avg_rsrp, 2)              AS avg_rsrp,
    ROUND(sq.avg_sinr, 2)              AS avg_sinr,
    ROUND(sq.avg_prb_utilization, 2)   AS prb_utilization,
    ROUND(sq.avg_dl_throughput_mbps, 2) AS dl_throughput,
    ROUND(sq.avg_ul_throughput_mbps, 2) AS ul_throughput,
    CAST(sq.avg_connected_ues AS INT)   AS connected_ues,
    ROUND(sq.poor_quality_ratio, 4)     AS poor_quality_ratio,
    classify_signal(sq.avg_rsrp, sq.avg_sinr, NULL) AS quality_class,
    -- Capacity utilization status
    CASE
        WHEN sq.avg_prb_utilization > 95 THEN 'CRITICAL'
        WHEN sq.avg_prb_utilization > 80 THEN 'WARNING'
        WHEN sq.avg_prb_utilization > 60 THEN 'MODERATE'
        ELSE 'HEALTHY'
    END AS capacity_status,
    -- Trend: compare with previous bucket
    ROUND(sq.avg_rsrp - LAG(sq.avg_rsrp) OVER (
        PARTITION BY sq.tower_id, sq.sector_id
        ORDER BY sq.time_bucket
    ), 2) AS rsrp_delta,
    ROUND(sq.avg_prb_utilization - LAG(sq.avg_prb_utilization) OVER (
        PARTITION BY sq.tower_id, sq.sector_id
        ORDER BY sq.time_bucket
    ), 2) AS prb_util_delta
FROM telco_processed.signal_quality_agg sq
JOIN telco_raw.tower_metadata tm ON sq.tower_id = tm.tower_id
WHERE sq.dt = '${hivevar:report_date}'
  AND tm.is_active = true
ORDER BY sq.tower_id, sq.sector_id, sq.time_bucket;
