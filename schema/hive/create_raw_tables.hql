-- ============================================================================
-- Telco Data Pipeline: Raw Zone Hive Tables
-- ============================================================================
-- Raw landing zone tables for ingested telco data.
-- Partitioned by date and region for efficient querying and lifecycle management.
-- Data retention: 90 days in raw zone before archival to cold storage.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS telco_raw
COMMENT 'Raw ingested telco data - tower signals, handoffs, CDRs'
LOCATION '/data/raw';

USE telco_raw;

-- ---------------------------------------------------------------------------
-- Tower Signal Measurements (raw)
-- Source: Kafka consumer writing Avro files to HDFS
-- Volume: ~500M records/day across all towers
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS tower_signals_raw (
    signal_id               STRING      COMMENT 'UUID for this measurement sample',
    tower_id                STRING      COMMENT 'Cell tower identifier (MCC-MNC-LAC-CID)',
    sector_id               INT         COMMENT 'Antenna sector index (0-based)',
    timestamp_ms            BIGINT      COMMENT 'Measurement timestamp (epoch ms)',
    signal_strength_dbm     DOUBLE      COMMENT 'RSSI in dBm',
    signal_to_noise_ratio   DOUBLE      COMMENT 'SNR in dB',
    rsrp                    DOUBLE      COMMENT 'Reference Signal Received Power (dBm)',
    rsrq                    DOUBLE      COMMENT 'Reference Signal Received Quality (dB)',
    sinr                    DOUBLE      COMMENT 'Signal-to-Interference-plus-Noise Ratio (dB)',
    cqi                     INT         COMMENT 'Channel Quality Indicator (0-15)',
    timing_advance          INT         COMMENT 'UE distance indicator (0-1282)',
    frequency_band          STRING      COMMENT 'Operating frequency band',
    technology              STRING      COMMENT 'Radio access technology (LTE, NR, etc.)',
    pci                     INT         COMMENT 'Physical Cell Identity',
    earfcn                  INT         COMMENT 'EARFCN channel number',
    connected_ues           INT         COMMENT 'Connected UE count',
    prb_utilization_pct     DOUBLE      COMMENT 'PRB utilization percentage',
    throughput_dl_mbps      DOUBLE      COMMENT 'Downlink throughput (Mbps)',
    throughput_ul_mbps      DOUBLE      COMMENT 'Uplink throughput (Mbps)',
    latitude                DOUBLE      COMMENT 'Tower latitude',
    longitude               DOUBLE      COMMENT 'Tower longitude',
    antenna_height_m        DOUBLE      COMMENT 'Antenna height (m)',
    antenna_azimuth_deg     DOUBLE      COMMENT 'Antenna azimuth (degrees)',
    antenna_tilt_deg        DOUBLE      COMMENT 'Antenna tilt (degrees)',
    interference_level_dbm  DOUBLE      COMMENT 'Interference level (dBm)',
    weather_condition       STRING      COMMENT 'Weather at tower site',
    temperature_celsius     DOUBLE      COMMENT 'Ambient temperature'
)
PARTITIONED BY (
    dt      STRING  COMMENT 'Date partition (yyyy-MM-dd)',
    region  STRING  COMMENT 'Geographic region'
)
STORED AS AVRO
LOCATION '/data/raw/tower-signals'
TBLPROPERTIES (
    'avro.schema.url'='/schema/avro/tower_signal.avsc',
    'auto.purge'='true',
    'transient_lastDdlTime'='auto'
);

-- ---------------------------------------------------------------------------
-- Handoff Events (raw)
-- Source: MME/AMF handoff event collector via Kafka
-- Volume: ~50M events/day
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS handoff_events_raw (
    event_id                    STRING      COMMENT 'Unique handoff event ID',
    imsi_hash                   STRING      COMMENT 'SHA-256 hash of subscriber IMSI',
    msisdn_hash                 STRING      COMMENT 'SHA-256 hash of phone number',
    call_id                     STRING      COMMENT 'Active call ID (null if idle)',
    event_timestamp_ms          BIGINT      COMMENT 'Event timestamp (epoch ms)',
    handoff_trigger_timestamp_ms BIGINT     COMMENT 'Trigger timestamp',
    handoff_complete_timestamp_ms BIGINT    COMMENT 'Completion timestamp (null if failed)',
    source_tower_id             STRING      COMMENT 'Source cell tower',
    source_sector_id            INT         COMMENT 'Source sector',
    source_pci                  INT         COMMENT 'Source PCI',
    target_tower_id             STRING      COMMENT 'Target cell tower',
    target_sector_id            INT         COMMENT 'Target sector',
    target_pci                  INT         COMMENT 'Target PCI',
    handoff_type                STRING      COMMENT 'Type (INTRA_FREQ, INTER_RAT, etc.)',
    handoff_cause               STRING      COMMENT 'Cause (SIGNAL_DEGRADATION, etc.)',
    result                      STRING      COMMENT 'Outcome (SUCCESS, FAILURE_*)',
    source_rsrp_dbm             DOUBLE      COMMENT 'Source RSRP at trigger',
    target_rsrp_dbm             DOUBLE      COMMENT 'Target RSRP at trigger',
    source_rsrq_db              DOUBLE      COMMENT 'Source RSRQ',
    target_rsrq_db              DOUBLE      COMMENT 'Target RSRQ',
    ue_velocity_kmh             DOUBLE      COMMENT 'Estimated UE velocity',
    ue_latitude                 DOUBLE      COMMENT 'UE latitude at handoff',
    ue_longitude                DOUBLE      COMMENT 'UE longitude at handoff',
    handoff_duration_ms         BIGINT      COMMENT 'Handoff execution time',
    data_interruption_ms        BIGINT      COMMENT 'Data plane interruption',
    hysteresis_db               DOUBLE      COMMENT 'Configured hysteresis',
    time_to_trigger_ms          INT         COMMENT 'Time-to-trigger parameter',
    a3_offset_db                DOUBLE      COMMENT 'A3 event offset',
    neighbor_cell_count         INT         COMMENT 'Cells in measurement report',
    is_voice_call_active        BOOLEAN     COMMENT 'Voice call active flag',
    is_volte                    BOOLEAN     COMMENT 'VoLTE flag',
    qci                         INT         COMMENT 'QoS Class Identifier',
    tracking_area_change        BOOLEAN     COMMENT 'TA boundary crossed'
)
PARTITIONED BY (
    dt      STRING  COMMENT 'Date partition (yyyy-MM-dd)',
    region  STRING  COMMENT 'Geographic region'
)
STORED AS AVRO
LOCATION '/data/raw/handoff-events'
TBLPROPERTIES (
    'avro.schema.url'='/schema/avro/handoff_event.avsc'
);

-- ---------------------------------------------------------------------------
-- Call Detail Records (raw)
-- Source: MSC/IMS voice core via Flume
-- Volume: ~100M CDRs/day
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS call_detail_records_raw (
    cdr_id                  STRING      COMMENT 'Unique CDR identifier',
    call_id                 STRING      COMMENT 'Call session identifier',
    caller_imsi_hash        STRING      COMMENT 'Caller IMSI hash',
    callee_imsi_hash        STRING      COMMENT 'Callee IMSI hash',
    caller_msisdn_hash      STRING      COMMENT 'Caller phone hash',
    callee_msisdn_hash      STRING      COMMENT 'Callee phone hash',
    call_type               STRING      COMMENT 'Call type (VOICE_MO, VOLTE_MO, etc.)',
    call_setup_timestamp_ms BIGINT      COMMENT 'Call setup initiation',
    call_connect_timestamp_ms BIGINT    COMMENT 'Call answer timestamp',
    call_end_timestamp_ms   BIGINT      COMMENT 'Call termination timestamp',
    call_duration_seconds   INT         COMMENT 'Billable duration (seconds)',
    setup_time_ms           BIGINT      COMMENT 'Setup to ringing time',
    termination_cause       STRING      COMMENT 'Termination reason',
    is_dropped              BOOLEAN     COMMENT 'Abnormally dropped flag',
    originating_tower_id    STRING      COMMENT 'Call origination tower',
    originating_sector_id   INT         COMMENT 'Call origination sector',
    terminating_tower_id    STRING      COMMENT 'Call termination tower',
    terminating_sector_id   INT         COMMENT 'Call termination sector',
    towers_traversed        ARRAY<STRING> COMMENT 'Tower IDs traversed during call',
    handoff_count           INT         COMMENT 'Successful handoff count',
    failed_handoff_count    INT         COMMENT 'Failed handoff count',
    avg_signal_strength_dbm DOUBLE      COMMENT 'Average RSSI during call',
    min_signal_strength_dbm DOUBLE      COMMENT 'Minimum RSSI during call',
    avg_sinr_db             DOUBLE      COMMENT 'Average SINR during call',
    mos_score               DOUBLE      COMMENT 'Mean Opinion Score (1.0-5.0)',
    jitter_ms               DOUBLE      COMMENT 'Average jitter (ms)',
    packet_loss_pct         DOUBLE      COMMENT 'Packet loss percentage',
    codec                   STRING      COMMENT 'Voice codec',
    roaming_type            STRING      COMMENT 'HOME, NATIONAL, INTERNATIONAL',
    rat_type_start          STRING      COMMENT 'RAT at call start',
    rat_type_end            STRING      COMMENT 'RAT at call end',
    srvcc_performed         BOOLEAN     COMMENT 'SRVCC performed flag',
    csfb_performed          BOOLEAN     COMMENT 'CSFB performed flag'
)
PARTITIONED BY (
    dt      STRING  COMMENT 'Date partition (yyyy-MM-dd)',
    region  STRING  COMMENT 'Geographic region'
)
STORED AS AVRO
LOCATION '/data/raw/cdr'
TBLPROPERTIES (
    'avro.schema.url'='/schema/avro/call_detail_record.avsc'
);

-- ---------------------------------------------------------------------------
-- Tower Metadata (dimension table)
-- Source: Sqoop import from network inventory RDBMS
-- Updated daily via incremental import
-- ---------------------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS tower_metadata (
    tower_id                STRING,
    site_id                 STRING,
    site_name               STRING,
    mcc                     STRING,
    mnc                     STRING,
    lac                     INT,
    cell_id                 INT,
    enodeb_id               INT,
    latitude                DOUBLE,
    longitude               DOUBLE,
    elevation_m             DOUBLE,
    tower_height_m          DOUBLE,
    tower_type              STRING,
    vendor                  STRING,
    hardware_model          STRING,
    firmware_version        STRING,
    backhaul_type           STRING,
    backhaul_capacity_mbps  INT,
    power_source            STRING,
    battery_backup_hours    DOUBLE,
    install_date            STRING,
    last_maintenance_date   STRING,
    region                  STRING,
    market                  STRING,
    is_active               BOOLEAN,
    indoor_outdoor          STRING,
    morphology              STRING
)
STORED AS PARQUET
LOCATION '/data/dimension/tower-metadata'
TBLPROPERTIES ('parquet.compression'='SNAPPY');

-- MSCK to auto-discover partitions
MSCK REPAIR TABLE tower_signals_raw;
MSCK REPAIR TABLE handoff_events_raw;
MSCK REPAIR TABLE call_detail_records_raw;
