-- ============================================================================
-- Daily Tower Metrics Aggregation
-- ============================================================================
-- Produces daily per-tower KPIs by joining signal quality, CDR, and handoff data.
-- Scheduled via Oozie coordinator to run daily at 02:00 UTC.
-- ============================================================================

SET hive.exec.dynamic.partition=true;
SET hive.exec.dynamic.partition.mode=nonstrict;
SET hive.vectorized.execution.enabled=true;
SET hive.vectorized.execution.reduce.enabled=true;
SET mapreduce.job.reduces=50;

ADD JAR /opt/telco/lib/telco-hive-udfs.jar;
CREATE TEMPORARY FUNCTION classify_signal AS 'com.telco.pipeline.hive.udf.SignalStrengthClassifier';
CREATE TEMPORARY FUNCTION geo_distance AS 'com.telco.pipeline.hive.udf.GeoDistanceCalculator';

-- Daily signal quality summary per tower
INSERT OVERWRITE TABLE telco_analytics.hourly_tower_heatmap PARTITION (dt = '${hivevar:report_date}')
SELECT
    sq.tower_id,
    tm.latitude,
    tm.longitude,
    FLOOR((sq.time_bucket % 86400000) / 3600000) AS hour_of_day,
    AVG(sq.avg_rsrp)                              AS avg_rsrp,
    AVG(sq.avg_sinr)                              AS avg_sinr,
    AVG(sq.avg_prb_utilization)                    AS avg_prb_utilization,
    CAST(AVG(sq.avg_connected_ues) AS INT)         AS connected_ues,
    COALESCE(dc.dropped_count, 0)                  AS dropped_call_count,
    COALESCE(hf.failure_count, 0)                  AS handoff_failure_count,
    classify_signal(AVG(sq.avg_rsrp))              AS signal_quality_class
FROM telco_processed.signal_quality_agg sq
JOIN telco_raw.tower_metadata tm ON sq.tower_id = tm.tower_id
LEFT JOIN (
    SELECT originating_tower_id AS tower_id,
           FLOOR((call_setup_timestamp_ms % 86400000) / 3600000) AS hour_of_day,
           COUNT(*) AS dropped_count
    FROM telco_raw.call_detail_records_raw
    WHERE dt = '${hivevar:report_date}' AND is_dropped = true
    GROUP BY originating_tower_id, FLOOR((call_setup_timestamp_ms % 86400000) / 3600000)
) dc ON sq.tower_id = dc.tower_id
    AND FLOOR((sq.time_bucket % 86400000) / 3600000) = dc.hour_of_day
LEFT JOIN (
    SELECT source_tower_id AS tower_id,
           FLOOR((event_timestamp_ms % 86400000) / 3600000) AS hour_of_day,
           COUNT(*) AS failure_count
    FROM telco_raw.handoff_events_raw
    WHERE dt = '${hivevar:report_date}' AND result != 'SUCCESS'
    GROUP BY source_tower_id, FLOOR((event_timestamp_ms % 86400000) / 3600000)
) hf ON sq.tower_id = hf.tower_id
    AND FLOOR((sq.time_bucket % 86400000) / 3600000) = hf.hour_of_day
WHERE sq.dt = '${hivevar:report_date}'
GROUP BY
    sq.tower_id, tm.latitude, tm.longitude,
    FLOOR((sq.time_bucket % 86400000) / 3600000),
    COALESCE(dc.dropped_count, 0),
    COALESCE(hf.failure_count, 0);

-- Daily network KPIs by region
INSERT OVERWRITE TABLE telco_analytics.daily_network_kpis PARTITION (dt = '${hivevar:report_date}')
SELECT
    '${hivevar:report_date}'                                AS report_date,
    tm.region,
    COUNT(DISTINCT tm.tower_id)                             AS total_towers,
    COUNT(DISTINCT CASE WHEN tm.is_active THEN tm.tower_id END) AS active_towers,
    COALESCE(cdr.total_calls, 0)                            AS total_calls,
    COALESCE(cdr.dropped_calls, 0)                          AS total_dropped_calls,
    COALESCE(cdr.dropped_calls * 1.0 / NULLIF(cdr.total_calls, 0), 0) AS network_dropped_rate,
    COALESCE(cdr.avg_mos, 0)                                AS avg_mos_score,
    COALESCE(ho.total_attempts, 0)                          AS total_handoff_attempts,
    COALESCE(ho.total_failures, 0)                          AS total_handoff_failures,
    COALESCE(1.0 - ho.total_failures * 1.0 / NULLIF(ho.total_attempts, 0), 0) AS handoff_success_rate,
    sq.avg_rsrp                                             AS avg_rsrp,
    sq.avg_sinr                                             AS avg_sinr,
    sq.avg_prb_util                                         AS avg_prb_utilization,
    sq.peak_prb_util                                        AS peak_prb_utilization,
    sq.avg_dl_throughput                                     AS avg_dl_throughput_mbps,
    sq.avg_ul_throughput                                     AS avg_ul_throughput_mbps,
    CAST(sq.total_ues AS BIGINT)                            AS total_connected_ues,
    CAST(sq.peak_ues AS BIGINT)                             AS peak_connected_ues,
    sq.excellent_pct, sq.good_pct, sq.fair_pct, sq.poor_pct,
    COALESCE(sc.tier1_count, 0)                             AS tier1_tower_count,
    COALESCE(sc.tier5_count, 0)                             AS tier5_tower_count
FROM telco_raw.tower_metadata tm
LEFT JOIN (
    SELECT region,
           AVG(avg_rsrp) AS avg_rsrp, AVG(avg_sinr) AS avg_sinr,
           AVG(avg_prb_utilization) AS avg_prb_util,
           MAX(avg_prb_utilization) AS peak_prb_util,
           AVG(avg_dl_throughput_mbps) AS avg_dl_throughput,
           AVG(avg_ul_throughput_mbps) AS avg_ul_throughput,
           SUM(avg_connected_ues) AS total_ues,
           MAX(avg_connected_ues) AS peak_ues,
           SUM(CASE WHEN classify_signal(avg_rsrp) = 'EXCELLENT' THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS excellent_pct,
           SUM(CASE WHEN classify_signal(avg_rsrp) = 'GOOD' THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS good_pct,
           SUM(CASE WHEN classify_signal(avg_rsrp) = 'FAIR' THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS fair_pct,
           SUM(CASE WHEN classify_signal(avg_rsrp) = 'POOR' THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS poor_pct
    FROM telco_processed.signal_quality_agg s
    JOIN telco_raw.tower_metadata t ON s.tower_id = t.tower_id
    WHERE s.dt = '${hivevar:report_date}'
    GROUP BY region
) sq ON tm.region = sq.region
LEFT JOIN (
    SELECT t.region,
           COUNT(*) AS total_calls,
           SUM(CASE WHEN is_dropped THEN 1 ELSE 0 END) AS dropped_calls,
           AVG(mos_score) AS avg_mos
    FROM telco_raw.call_detail_records_raw c
    JOIN telco_raw.tower_metadata t ON c.originating_tower_id = t.tower_id
    WHERE c.dt = '${hivevar:report_date}'
    GROUP BY t.region
) cdr ON tm.region = cdr.region
LEFT JOIN (
    SELECT t.region,
           COUNT(*) AS total_attempts,
           SUM(CASE WHEN result != 'SUCCESS' THEN 1 ELSE 0 END) AS total_failures
    FROM telco_raw.handoff_events_raw h
    JOIN telco_raw.tower_metadata t ON h.source_tower_id = t.tower_id
    WHERE h.dt = '${hivevar:report_date}'
    GROUP BY t.region
) ho ON tm.region = ho.region
LEFT JOIN (
    SELECT region,
           SUM(CASE WHEN performance_tier = 'TIER_1' THEN 1 ELSE 0 END) AS tier1_count,
           SUM(CASE WHEN performance_tier = 'TIER_5_REMEDIATION' THEN 1 ELSE 0 END) AS tier5_count
    FROM telco_processed.tower_scorecards s
    JOIN telco_raw.tower_metadata t ON s.tower_id = t.tower_id
    WHERE s.dt = '${hivevar:report_date}'
    GROUP BY region
) sc ON tm.region = sc.region
WHERE tm.is_active = true
GROUP BY tm.region, cdr.total_calls, cdr.dropped_calls, cdr.avg_mos,
         ho.total_attempts, ho.total_failures,
         sq.avg_rsrp, sq.avg_sinr, sq.avg_prb_util, sq.peak_prb_util,
         sq.avg_dl_throughput, sq.avg_ul_throughput,
         sq.total_ues, sq.peak_ues,
         sq.excellent_pct, sq.good_pct, sq.fair_pct, sq.poor_pct,
         sc.tier1_count, sc.tier5_count;
