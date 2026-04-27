-- ============================================================================
-- Telco Data Pipeline: Aggregated / Analytics Zone Tables
-- ============================================================================
-- Pre-computed aggregations for dashboards and reporting.
-- Materialized daily for fast query response times.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS telco_analytics
COMMENT 'Pre-aggregated analytics tables for dashboards and reporting'
LOCATION '/data/analytics';

USE telco_analytics;

-- ---------------------------------------------------------------------------
-- Daily Network KPIs
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS daily_network_kpis (
    report_date             STRING,
    region                  STRING,
    total_towers            INT,
    active_towers           INT,
    total_calls             BIGINT,
    total_dropped_calls     BIGINT,
    network_dropped_rate    DOUBLE,
    avg_mos_score           DOUBLE,
    total_handoff_attempts  BIGINT,
    total_handoff_failures  BIGINT,
    handoff_success_rate    DOUBLE,
    avg_rsrp               DOUBLE,
    avg_sinr               DOUBLE,
    avg_prb_utilization     DOUBLE,
    peak_prb_utilization    DOUBLE,
    avg_dl_throughput_mbps  DOUBLE,
    avg_ul_throughput_mbps  DOUBLE,
    total_connected_ues     BIGINT,
    peak_connected_ues      BIGINT,
    coverage_excellent_pct  DOUBLE,
    coverage_good_pct       DOUBLE,
    coverage_fair_pct       DOUBLE,
    coverage_poor_pct       DOUBLE,
    tier1_tower_count       INT,
    tier5_tower_count       INT
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
TBLPROPERTIES ('parquet.compression'='SNAPPY');

-- ---------------------------------------------------------------------------
-- Hourly Tower Heatmap Data
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hourly_tower_heatmap (
    tower_id                STRING,
    latitude                DOUBLE,
    longitude               DOUBLE,
    hour_of_day             INT,
    avg_rsrp                DOUBLE,
    avg_sinr                DOUBLE,
    avg_prb_utilization     DOUBLE,
    connected_ues           INT,
    dropped_call_count      INT,
    handoff_failure_count   INT,
    signal_quality_class    STRING
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
TBLPROPERTIES ('parquet.compression'='SNAPPY');

-- ---------------------------------------------------------------------------
-- Weekly Trend Analysis
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS weekly_trend_analysis (
    week_start              STRING,
    tower_id                STRING,
    region                  STRING,
    market                  STRING,
    avg_composite_score     DOUBLE,
    score_trend             DOUBLE      COMMENT 'WoW change in composite score',
    avg_dropped_rate        DOUBLE,
    dropped_rate_trend      DOUBLE      COMMENT 'WoW change in drop rate',
    avg_prb_utilization     DOUBLE,
    prb_trend               DOUBLE      COMMENT 'WoW change in PRB util',
    avg_connected_ues       DOUBLE,
    ue_growth_rate          DOUBLE      COMMENT 'WoW UE growth percentage',
    capacity_risk           STRING      COMMENT 'LOW/MEDIUM/HIGH/CRITICAL'
)
STORED AS PARQUET
TBLPROPERTIES ('parquet.compression'='SNAPPY');

-- ---------------------------------------------------------------------------
-- Top Problem Towers (daily refreshed view)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS top_problem_towers (
    tower_id                STRING,
    site_name               STRING,
    region                  STRING,
    market                  STRING,
    composite_score         DOUBLE,
    performance_tier        STRING,
    dropped_call_rate       DOUBLE,
    avg_mos                 DOUBLE,
    handoff_failure_rate    DOUBLE,
    avg_prb_utilization     DOUBLE,
    primary_issue           STRING,
    secondary_issue         STRING,
    days_in_tier5           INT,
    estimated_affected_subs INT,
    recommended_action      STRING
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
TBLPROPERTIES ('parquet.compression'='SNAPPY');
