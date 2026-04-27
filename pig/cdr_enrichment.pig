-- ============================================================================
-- CDR Enrichment Pipeline (Apache Pig)
-- ============================================================================
-- Enriches raw CDRs with tower metadata, computes derived quality metrics,
-- and classifies calls for downstream analytics.
--
-- Usage:
--   pig -param CDR_INPUT=/data/raw/cdr/dt=2024-01-15 \
--       -param TOWER_META=/data/dimension/tower-metadata \
--       -param OUTPUT=/data/processed/enriched-cdrs/dt=2024-01-15 \
--       cdr_enrichment.pig
-- ============================================================================

REGISTER /opt/telco/lib/telco-pig-udfs.jar;

-- Load raw CDRs
raw_cdrs = LOAD '$CDR_INPUT' USING AvroStorage() AS (
    cdr_id:chararray,
    call_id:chararray,
    caller_imsi_hash:chararray,
    callee_imsi_hash:chararray,
    call_type:chararray,
    call_setup_timestamp_ms:long,
    call_connect_timestamp_ms:long,
    call_end_timestamp_ms:long,
    call_duration_seconds:int,
    termination_cause:chararray,
    is_dropped:boolean,
    originating_tower_id:chararray,
    originating_sector_id:int,
    terminating_tower_id:chararray,
    terminating_sector_id:int,
    handoff_count:int,
    failed_handoff_count:int,
    avg_signal_strength_dbm:double,
    min_signal_strength_dbm:double,
    avg_sinr_db:double,
    mos_score:double,
    jitter_ms:double,
    packet_loss_pct:double,
    codec:chararray,
    roaming_type:chararray,
    rat_type_start:chararray,
    rat_type_end:chararray,
    srvcc_performed:boolean,
    csfb_performed:boolean
);

-- Load tower metadata
tower_meta = LOAD '$TOWER_META' USING ParquetLoader() AS (
    tower_id:chararray,
    site_name:chararray,
    region:chararray,
    market:chararray,
    morphology:chararray,
    tower_type:chararray
);

-- Join with originating tower metadata
with_orig = JOIN raw_cdrs BY originating_tower_id LEFT OUTER,
                tower_meta BY tower_id;

-- Join with terminating tower metadata
term_meta = LOAD '$TOWER_META' USING ParquetLoader() AS (
    tower_id:chararray,
    site_name:chararray,
    region:chararray,
    market:chararray,
    morphology:chararray,
    tower_type:chararray
);

enriched = JOIN with_orig BY raw_cdrs::terminating_tower_id LEFT OUTER,
               term_meta BY tower_id;

-- Project and compute derived fields
result = FOREACH enriched GENERATE
    raw_cdrs::cdr_id AS cdr_id,
    raw_cdrs::call_id AS call_id,
    raw_cdrs::call_type AS call_type,
    raw_cdrs::call_setup_timestamp_ms AS call_setup_timestamp_ms,
    raw_cdrs::call_end_timestamp_ms AS call_end_timestamp_ms,
    raw_cdrs::call_duration_seconds AS call_duration_seconds,
    raw_cdrs::termination_cause AS termination_cause,
    raw_cdrs::is_dropped AS is_dropped,
    raw_cdrs::originating_tower_id AS originating_tower_id,
    tower_meta::site_name AS originating_site_name,
    tower_meta::market AS originating_market,
    tower_meta::morphology AS originating_morphology,
    tower_meta::tower_type AS originating_tower_type,
    raw_cdrs::terminating_tower_id AS terminating_tower_id,
    term_meta::site_name AS terminating_site_name,
    term_meta::market AS terminating_market,
    raw_cdrs::handoff_count AS handoff_count,
    raw_cdrs::failed_handoff_count AS failed_handoff_count,
    raw_cdrs::avg_signal_strength_dbm AS avg_signal_strength_dbm,
    raw_cdrs::mos_score AS mos_score,
    raw_cdrs::roaming_type AS roaming_type,
    raw_cdrs::rat_type_start AS rat_type_start,
    raw_cdrs::rat_type_end AS rat_type_end,
    -- Call quality classification
    (CASE
        WHEN raw_cdrs::mos_score >= 4.0 THEN 'EXCELLENT'
        WHEN raw_cdrs::mos_score >= 3.5 THEN 'GOOD'
        WHEN raw_cdrs::mos_score >= 2.5 THEN 'FAIR'
        WHEN raw_cdrs::mos_score IS NOT NULL THEN 'POOR'
        WHEN raw_cdrs::is_dropped THEN 'POOR'
        ELSE 'UNKNOWN'
    END) AS call_quality_class;

STORE result INTO '$OUTPUT' USING ParquetStorer();
