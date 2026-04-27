-- ============================================================================
-- Tower Signal ETL Pipeline (Apache Pig)
-- ============================================================================
-- Transforms raw tower signal data into cleaned, validated, and enriched
-- records for loading into the processed Hive tables.
--
-- Steps:
-- 1. Load raw signal measurements
-- 2. Filter invalid/out-of-range values
-- 3. Enrich with tower metadata (join)
-- 4. Compute derived metrics (signal quality class, capacity utilization)
-- 5. Store as Parquet in processed zone
--
-- Usage:
--   pig -param INPUT=/data/raw/tower-signals/dt=2024-01-15 \
--       -param TOWER_META=/data/dimension/tower-metadata \
--       -param OUTPUT=/data/processed/signal-quality/dt=2024-01-15 \
--       tower_signal_etl.pig
-- ============================================================================

-- Register UDF jars
REGISTER /opt/telco/lib/telco-pig-udfs.jar;
REGISTER /opt/telco/lib/avro-1.11.3.jar;
REGISTER /opt/telco/lib/parquet-pig-bundle-1.13.1.jar;

-- Load raw signals
raw_signals = LOAD '$INPUT' USING AvroStorage() AS (
    signal_id:chararray,
    tower_id:chararray,
    sector_id:int,
    timestamp_ms:long,
    signal_strength_dbm:double,
    signal_to_noise_ratio:double,
    rsrp:double,
    rsrq:double,
    sinr:double,
    cqi:int,
    timing_advance:int,
    frequency_band:chararray,
    technology:chararray,
    pci:int,
    earfcn:int,
    connected_ues:int,
    prb_utilization_pct:double,
    throughput_dl_mbps:double,
    throughput_ul_mbps:double,
    latitude:double,
    longitude:double,
    antenna_height_m:double,
    antenna_azimuth_deg:double,
    antenna_tilt_deg:double,
    interference_level_dbm:double,
    weather_condition:chararray,
    temperature_celsius:double
);

-- Step 1: Filter invalid records
valid_signals = FILTER raw_signals BY
    tower_id IS NOT NULL
    AND timestamp_ms > 0
    AND signal_strength_dbm >= -140.0 AND signal_strength_dbm <= -20.0
    AND rsrp >= -156.0 AND rsrp <= -31.0
    AND rsrq >= -20.0 AND rsrq <= -3.0
    AND sinr >= -20.0 AND sinr <= 30.0
    AND cqi >= 0 AND cqi <= 15
    AND connected_ues >= 0
    AND prb_utilization_pct >= 0.0 AND prb_utilization_pct <= 100.0;

-- Step 2: Load tower metadata for enrichment
tower_meta = LOAD '$TOWER_META' USING ParquetLoader() AS (
    tower_id:chararray,
    site_id:chararray,
    site_name:chararray,
    region:chararray,
    market:chararray,
    morphology:chararray,
    tower_type:chararray,
    is_active:boolean
);

-- Step 3: Join signals with tower metadata
enriched = JOIN valid_signals BY tower_id, tower_meta BY tower_id;

-- Step 4: Compute derived fields
processed = FOREACH enriched GENERATE
    valid_signals::signal_id AS signal_id,
    valid_signals::tower_id AS tower_id,
    tower_meta::site_name AS site_name,
    tower_meta::region AS region,
    tower_meta::market AS market,
    tower_meta::morphology AS morphology,
    valid_signals::sector_id AS sector_id,
    valid_signals::timestamp_ms AS timestamp_ms,
    -- Compute 15-minute time bucket
    (valid_signals::timestamp_ms / 900000L) * 900000L AS time_bucket,
    valid_signals::signal_strength_dbm AS rssi,
    valid_signals::rsrp AS rsrp,
    valid_signals::rsrq AS rsrq,
    valid_signals::sinr AS sinr,
    valid_signals::cqi AS cqi,
    valid_signals::connected_ues AS connected_ues,
    valid_signals::prb_utilization_pct AS prb_utilization,
    valid_signals::throughput_dl_mbps AS dl_throughput,
    valid_signals::throughput_ul_mbps AS ul_throughput,
    valid_signals::frequency_band AS frequency_band,
    valid_signals::technology AS technology,
    valid_signals::interference_level_dbm AS interference_level,
    -- Signal quality classification
    (CASE
        WHEN valid_signals::rsrp >= -80.0 AND valid_signals::sinr >= 20.0
            AND valid_signals::cqi >= 12 THEN 'EXCELLENT'
        WHEN valid_signals::rsrp >= -90.0 AND valid_signals::sinr >= 13.0
            AND valid_signals::cqi >= 7 THEN 'GOOD'
        WHEN valid_signals::rsrp >= -100.0 AND valid_signals::sinr >= 0.0
            AND valid_signals::cqi >= 4 THEN 'FAIR'
        WHEN valid_signals::rsrp >= -115.0 THEN 'POOR'
        ELSE 'NO_COVERAGE'
    END) AS quality_class,
    -- Capacity status
    (CASE
        WHEN valid_signals::prb_utilization_pct > 95.0 THEN 'CRITICAL'
        WHEN valid_signals::prb_utilization_pct > 80.0 THEN 'WARNING'
        WHEN valid_signals::prb_utilization_pct > 60.0 THEN 'MODERATE'
        ELSE 'HEALTHY'
    END) AS capacity_status;

-- Step 5: Aggregate by tower-sector-bucket
grouped = GROUP processed BY (tower_id, sector_id, time_bucket);

aggregated = FOREACH grouped GENERATE
    FLATTEN(group) AS (tower_id, sector_id, time_bucket),
    COUNT(processed) AS sample_count,
    AVG(processed.rssi) AS avg_rssi,
    AVG(processed.rsrp) AS avg_rsrp,
    AVG(processed.sinr) AS avg_sinr,
    MIN(processed.rssi) AS min_rssi,
    MAX(processed.rssi) AS max_rssi,
    MIN(processed.rsrp) AS min_rsrp,
    MAX(processed.rsrp) AS max_rsrp,
    AVG(processed.prb_utilization) AS avg_prb_utilization,
    AVG(processed.dl_throughput) AS avg_dl_throughput,
    AVG(processed.ul_throughput) AS avg_ul_throughput,
    AVG(processed.connected_ues) AS avg_connected_ues,
    -- Count poor quality samples / total
    (double) SIZE(FILTER processed BY quality_class == 'POOR'
        OR quality_class == 'NO_COVERAGE') / COUNT(processed) AS poor_quality_ratio;

-- Store results
STORE aggregated INTO '$OUTPUT' USING ParquetStorer();

-- Log statistics
stats = FOREACH (GROUP aggregated ALL) GENERATE
    COUNT(aggregated) AS total_buckets,
    SUM(aggregated.sample_count) AS total_samples,
    AVG(aggregated.avg_rsrp) AS overall_avg_rsrp,
    AVG(aggregated.poor_quality_ratio) AS overall_poor_ratio;

DUMP stats;
