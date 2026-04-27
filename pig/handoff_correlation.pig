-- ============================================================================
-- Handoff Event Correlation Pipeline (Apache Pig)
-- ============================================================================
-- Correlates handoff events with concurrent tower signal measurements
-- to build training data for the handoff prediction ML model.
--
-- Usage:
--   pig -param HANDOFF_INPUT=/data/raw/handoff-events/dt=2024-01-15 \
--       -param SIGNAL_INPUT=/data/processed/signal-quality/dt=2024-01-15 \
--       -param TOWER_META=/data/dimension/tower-metadata \
--       -param OUTPUT=/data/ml/handoff-training/dt=2024-01-15 \
--       handoff_correlation.pig
-- ============================================================================

REGISTER /opt/telco/lib/telco-pig-udfs.jar;

-- Load handoff events
handoffs = LOAD '$HANDOFF_INPUT' USING AvroStorage() AS (
    event_id:chararray,
    imsi_hash:chararray,
    event_timestamp_ms:long,
    source_tower_id:chararray,
    source_sector_id:int,
    target_tower_id:chararray,
    target_sector_id:int,
    handoff_type:chararray,
    handoff_cause:chararray,
    result:chararray,
    source_rsrp_dbm:double,
    target_rsrp_dbm:double,
    source_rsrq_db:double,
    target_rsrq_db:double,
    ue_velocity_kmh:double,
    handoff_duration_ms:long,
    data_interruption_ms:long,
    hysteresis_db:double,
    time_to_trigger_ms:int,
    a3_offset_db:double,
    neighbor_cell_count:int,
    is_voice_call_active:boolean,
    is_volte:boolean
);

-- Load signal quality aggregates (15-min buckets)
signals = LOAD '$SIGNAL_INPUT' USING ParquetLoader() AS (
    tower_id:chararray,
    sector_id:int,
    time_bucket:long,
    sample_count:int,
    avg_rsrp:double,
    avg_sinr:double,
    avg_prb_utilization:double,
    avg_dl_throughput:double,
    avg_ul_throughput:double,
    avg_connected_ues:double,
    poor_quality_ratio:double
);

-- Compute time bucket for each handoff to align with signal data
handoffs_bucketed = FOREACH handoffs GENERATE
    *,
    (event_timestamp_ms / 900000L) * 900000L AS time_bucket;

-- Join handoffs with source tower signal conditions
with_source_signal = JOIN handoffs_bucketed BY (source_tower_id, source_sector_id, time_bucket),
                          signals BY (tower_id, sector_id, time_bucket);

-- Join with target tower signal conditions
target_signals = LOAD '$SIGNAL_INPUT' USING ParquetLoader() AS (
    tower_id:chararray,
    sector_id:int,
    time_bucket:long,
    sample_count:int,
    avg_rsrp:double,
    avg_sinr:double,
    avg_prb_utilization:double,
    avg_dl_throughput:double,
    avg_ul_throughput:double,
    avg_connected_ues:double,
    poor_quality_ratio:double
);

with_both_signals = JOIN with_source_signal
    BY (handoffs_bucketed::target_tower_id, handoffs_bucketed::target_sector_id,
        handoffs_bucketed::time_bucket) LEFT OUTER,
    target_signals BY (tower_id, sector_id, time_bucket);

-- Load tower metadata for distance calculation
tower_meta = LOAD '$TOWER_META' USING ParquetLoader() AS (
    tower_id:chararray,
    latitude:double,
    longitude:double,
    morphology:chararray,
    tower_type:chararray
);

-- Build training dataset
training_data = FOREACH with_both_signals GENERATE
    handoffs_bucketed::event_id AS event_id,
    handoffs_bucketed::event_timestamp_ms AS event_timestamp_ms,
    handoffs_bucketed::handoff_type AS handoff_type,
    handoffs_bucketed::handoff_cause AS handoff_cause,
    handoffs_bucketed::result AS result,
    handoffs_bucketed::source_rsrp_dbm AS source_rsrp_dbm,
    handoffs_bucketed::target_rsrp_dbm AS target_rsrp_dbm,
    handoffs_bucketed::source_rsrq_db AS source_rsrq_db,
    handoffs_bucketed::target_rsrq_db AS target_rsrq_db,
    handoffs_bucketed::ue_velocity_kmh AS ue_velocity_kmh,
    handoffs_bucketed::hysteresis_db AS hysteresis_db,
    handoffs_bucketed::time_to_trigger_ms AS time_to_trigger_ms,
    handoffs_bucketed::a3_offset_db AS a3_offset_db,
    handoffs_bucketed::neighbor_cell_count AS neighbor_cell_count,
    handoffs_bucketed::is_voice_call_active AS is_voice_call_active,
    -- Source tower conditions
    signals::avg_prb_utilization AS source_prb_util,
    signals::avg_connected_ues AS source_connected_ues,
    signals::avg_sinr AS source_tower_sinr,
    signals::poor_quality_ratio AS source_poor_quality,
    -- Target tower conditions
    target_signals::avg_prb_utilization AS target_prb_util,
    target_signals::avg_connected_ues AS target_connected_ues,
    target_signals::avg_sinr AS target_tower_sinr,
    target_signals::poor_quality_ratio AS target_poor_quality,
    -- Label
    (CASE WHEN handoffs_bucketed::result == 'SUCCESS' THEN 0 ELSE 1 END) AS label;

STORE training_data INTO '$OUTPUT' USING ParquetStorer();
