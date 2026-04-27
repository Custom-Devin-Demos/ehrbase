-- ============================================================================
-- Handoff Failure Analysis Query
-- ============================================================================
-- Identifies problematic tower pairs with high handoff failure rates
-- and correlates with signal quality and network configuration.
-- ============================================================================

SET hive.vectorized.execution.enabled=true;

ADD JAR /opt/telco/lib/telco-hive-udfs.jar;
CREATE TEMPORARY FUNCTION geo_distance AS 'com.telco.pipeline.hive.udf.GeoDistanceCalculator';

-- Top 50 worst-performing handoff corridors
SELECT
    hp.source_tower_id,
    src.site_name AS source_site,
    src.market AS source_market,
    hp.target_tower_id,
    tgt.site_name AS target_site,
    geo_distance(src.latitude, src.longitude, tgt.latitude, tgt.longitude) AS distance_km,
    SUM(hp.total_attempts) AS total_attempts,
    SUM(hp.failure_count) AS total_failures,
    ROUND(SUM(hp.failure_count) * 100.0 / SUM(hp.total_attempts), 2) AS failure_rate_pct,
    SUM(hp.ping_pong_count) AS total_ping_pongs,
    SUM(hp.voice_handoff_count) AS voice_handoffs_affected,
    ROUND(AVG(hp.avg_handoff_duration_ms), 1) AS avg_handoff_duration_ms,
    ROUND(AVG(hp.avg_data_interruption_ms), 1) AS avg_data_interruption_ms,
    ROUND(AVG(hp.avg_rsrp_differential), 2) AS avg_rsrp_diff,
    hp.dominant_handoff_type,
    hp.dominant_cause,
    -- Root cause classification
    CASE
        WHEN AVG(hp.avg_rsrp_differential) < -10 THEN 'COVERAGE_IMBALANCE'
        WHEN SUM(hp.ping_pong_count) > SUM(hp.total_attempts) * 0.1 THEN 'PARAMETER_MISCONFIGURATION'
        WHEN hp.dominant_cause = 'LOAD_BALANCING' THEN 'CAPACITY_ISSUE'
        WHEN geo_distance(src.latitude, src.longitude, tgt.latitude, tgt.longitude) > 5.0 THEN 'EXCESSIVE_DISTANCE'
        ELSE 'REQUIRES_INVESTIGATION'
    END AS root_cause_estimate,
    -- Recommended action
    CASE
        WHEN AVG(hp.avg_rsrp_differential) < -10 THEN 'Adjust antenna tilt/power to balance coverage'
        WHEN SUM(hp.ping_pong_count) > SUM(hp.total_attempts) * 0.1 THEN 'Increase hysteresis or TTT parameters'
        WHEN hp.dominant_cause = 'LOAD_BALANCING' THEN 'Review MLB thresholds; consider capacity expansion'
        WHEN geo_distance(src.latitude, src.longitude, tgt.latitude, tgt.longitude) > 5.0 THEN 'Add intermediate cell site'
        ELSE 'Schedule RAN engineer site visit'
    END AS recommended_action
FROM telco_processed.handoff_pair_analysis hp
JOIN telco_raw.tower_metadata src ON hp.source_tower_id = src.tower_id
JOIN telco_raw.tower_metadata tgt ON hp.target_tower_id = tgt.tower_id
WHERE hp.dt = '${hivevar:report_date}'
GROUP BY hp.source_tower_id, src.site_name, src.market,
         hp.target_tower_id, tgt.site_name,
         src.latitude, src.longitude, tgt.latitude, tgt.longitude,
         hp.dominant_handoff_type, hp.dominant_cause
HAVING SUM(hp.total_attempts) >= 50
   AND SUM(hp.failure_count) * 100.0 / SUM(hp.total_attempts) > 5.0
ORDER BY failure_rate_pct DESC
LIMIT 50;
