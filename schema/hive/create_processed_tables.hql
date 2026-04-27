-- ============================================================================
-- Telco Data Pipeline: Processed Zone Hive Tables
-- ============================================================================
-- Cleaned, validated, and enriched data ready for analytics.
-- Stored as Parquet with Snappy compression for optimal query performance.
-- Data retention: 1 year in processed zone.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS telco_processed
COMMENT 'Processed and enriched telco data'
LOCATION '/data/processed';

USE telco_processed;

-- ---------------------------------------------------------------------------
-- Aggregated Signal Quality (15-minute buckets)
-- Source: MapReduce signal quality aggregation job
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS signal_quality_agg (
    tower_id                STRING,
    sector_id               INT,
    time_bucket             BIGINT      COMMENT 'Bucket start timestamp (epoch ms)',
    sample_count            INT,
    avg_rssi                DOUBLE,
    avg_rsrp                DOUBLE,
    avg_sinr                DOUBLE,
    min_rssi                DOUBLE,
    max_rssi                DOUBLE,
    min_rsrp                DOUBLE,
    max_rsrp                DOUBLE,
    avg_prb_utilization     DOUBLE,
    avg_dl_throughput_mbps  DOUBLE,
    avg_ul_throughput_mbps  DOUBLE,
    avg_connected_ues       DOUBLE,
    poor_quality_ratio      DOUBLE      COMMENT 'Ratio of samples with poor signal'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/data/processed/signal-quality'
TBLPROPERTIES ('parquet.compression'='SNAPPY');

-- ---------------------------------------------------------------------------
-- Handoff Analysis Results (hourly aggregation per tower pair)
-- Source: MapReduce handoff pattern analysis job
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS handoff_pair_analysis (
    source_tower_id         STRING,
    target_tower_id         STRING,
    hour_bucket             BIGINT,
    total_attempts          INT,
    success_count           INT,
    failure_count           INT,
    success_rate            DOUBLE,
    avg_handoff_duration_ms DOUBLE,
    avg_data_interruption_ms DOUBLE,
    avg_rsrp_differential   DOUBLE,
    voice_handoff_count     INT,
    ping_pong_count         INT,
    dominant_handoff_type   STRING,
    dominant_cause          STRING
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/data/processed/handoff-analysis'
TBLPROPERTIES ('parquet.compression'='SNAPPY');

-- ---------------------------------------------------------------------------
-- CDR Tower Statistics (daily per-tower aggregation)
-- Source: MapReduce CDR processing job
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS cdr_tower_stats (
    tower_id                STRING,
    total_calls             INT,
    dropped_calls           INT,
    dropped_call_rate       DOUBLE,
    zero_duration_calls     INT,
    avg_call_duration_sec   DOUBLE,
    avg_handoffs_per_call   DOUBLE,
    avg_rssi_during_calls   DOUBLE,
    min_rssi_during_calls   DOUBLE,
    avg_sinr_during_calls   DOUBLE,
    avg_mos_score           DOUBLE,
    dominant_term_cause     STRING,
    dominant_call_type      STRING
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/data/processed/cdr-tower-stats'
TBLPROPERTIES ('parquet.compression'='SNAPPY');

-- ---------------------------------------------------------------------------
-- Tower Performance Scorecards (daily)
-- Source: Spark TowerPerformanceAnalyzer batch job
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS tower_scorecards (
    tower_id                STRING,
    signal_score            DOUBLE      COMMENT 'Signal quality composite (0-100)',
    call_quality_score      DOUBLE      COMMENT 'Call quality composite (0-100)',
    composite_score         DOUBLE      COMMENT 'Overall tower score (0-100)',
    performance_tier        STRING      COMMENT 'TIER_1 through TIER_5_REMEDIATION',
    overall_avg_rsrp        DOUBLE,
    overall_avg_sinr        DOUBLE,
    overall_avg_prb_util    DOUBLE,
    peak_connected_ues      DOUBLE,
    avg_poor_quality_ratio  DOUBLE,
    rsrp_stability          DOUBLE,
    worst_rsrp              DOUBLE,
    total_calls             INT,
    dropped_calls           INT,
    dropped_call_rate       DOUBLE,
    avg_mos                 DOUBLE,
    network_rank            INT,
    network_percentile      DOUBLE
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/data/processed/tower-scorecards'
TBLPROPERTIES ('parquet.compression'='SNAPPY');

-- ---------------------------------------------------------------------------
-- Enriched CDRs (denormalized with tower metadata)
-- Source: Pig CDR enrichment script
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS enriched_cdrs (
    cdr_id                  STRING,
    call_id                 STRING,
    call_type               STRING,
    call_setup_timestamp_ms BIGINT,
    call_end_timestamp_ms   BIGINT,
    call_duration_seconds   INT,
    termination_cause       STRING,
    is_dropped              BOOLEAN,
    originating_tower_id    STRING,
    originating_site_name   STRING,
    originating_market      STRING,
    originating_morphology  STRING,
    originating_tower_type  STRING,
    terminating_tower_id    STRING,
    terminating_site_name   STRING,
    terminating_market      STRING,
    handoff_count           INT,
    failed_handoff_count    INT,
    avg_signal_strength_dbm DOUBLE,
    mos_score               DOUBLE,
    roaming_type            STRING,
    rat_type_start          STRING,
    rat_type_end            STRING,
    call_quality_class      STRING      COMMENT 'EXCELLENT/GOOD/FAIR/POOR'
)
PARTITIONED BY (dt STRING, region STRING)
STORED AS PARQUET
LOCATION '/data/processed/enriched-cdrs'
TBLPROPERTIES ('parquet.compression'='SNAPPY');
