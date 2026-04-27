-- ============================================================================
-- Dropped Call Report
-- ============================================================================
-- Executive-level report on dropped calls by region, time, and cause.
-- ============================================================================

SET hive.vectorized.execution.enabled=true;
SET hive.map.aggr=true;

-- Summary by region and termination cause
SELECT
    tm.region,
    tm.market,
    cdr.termination_cause,
    COUNT(*) AS dropped_count,
    COUNT(DISTINCT cdr.caller_imsi_hash) AS unique_subscribers,
    COUNT(DISTINCT cdr.originating_tower_id) AS towers_involved,
    ROUND(AVG(cdr.call_duration_seconds), 1) AS avg_duration_before_drop,
    ROUND(AVG(cdr.avg_signal_strength_dbm), 2) AS avg_signal_at_drop,
    ROUND(AVG(cdr.min_signal_strength_dbm), 2) AS avg_min_signal,
    ROUND(AVG(cdr.handoff_count), 2) AS avg_handoffs_before_drop,
    ROUND(AVG(cdr.failed_handoff_count), 2) AS avg_failed_handoffs,
    ROUND(AVG(COALESCE(cdr.mos_score, 0)), 2) AS avg_mos_at_drop,
    SUM(CASE WHEN cdr.srvcc_performed THEN 1 ELSE 0 END) AS srvcc_drops,
    SUM(CASE WHEN cdr.csfb_performed THEN 1 ELSE 0 END) AS csfb_drops,
    SUM(CASE WHEN cdr.rat_type_start != cdr.rat_type_end THEN 1 ELSE 0 END) AS inter_rat_drops,
    -- Impact severity
    CASE
        WHEN COUNT(*) > 1000 THEN 'CRITICAL'
        WHEN COUNT(*) > 500 THEN 'HIGH'
        WHEN COUNT(*) > 100 THEN 'MEDIUM'
        ELSE 'LOW'
    END AS impact_severity
FROM telco_raw.call_detail_records_raw cdr
JOIN telco_raw.tower_metadata tm ON cdr.originating_tower_id = tm.tower_id
WHERE cdr.dt = '${hivevar:report_date}'
  AND cdr.is_dropped = true
GROUP BY tm.region, tm.market, cdr.termination_cause
ORDER BY dropped_count DESC;

-- Hourly distribution of dropped calls
SELECT
    FLOOR((call_setup_timestamp_ms % 86400000) / 3600000) AS hour_of_day,
    COUNT(*) AS dropped_count,
    COUNT(DISTINCT originating_tower_id) AS towers_affected,
    ROUND(AVG(avg_signal_strength_dbm), 2) AS avg_signal,
    termination_cause AS top_cause
FROM telco_raw.call_detail_records_raw
WHERE dt = '${hivevar:report_date}' AND is_dropped = true
GROUP BY FLOOR((call_setup_timestamp_ms % 86400000) / 3600000), termination_cause
ORDER BY hour_of_day, dropped_count DESC;
