# ============================================================================
# HBase Table Schema for Telco Real-Time Data
# ============================================================================
# Run: hbase shell < create_tables.rb
#
# Tables are designed for low-latency lookups from the NOC dashboard
# and real-time Spark streaming jobs.
# ============================================================================

# ---------------------------------------------------------------------------
# Tower State: current operational state of each tower/sector
# Row key: tower_id#sector_id (e.g., "310-260-1234-5678#0")
# Updated every 5 seconds from the real-time signal processor
# ---------------------------------------------------------------------------
create 'telco:tower_state', {
  NAME => 'signal',
  VERSIONS => 10,
  TTL => 86400,         # 24 hours retention for signal history
  COMPRESSION => 'LZ4',
  BLOOMFILTER => 'ROW',
  DATA_BLOCK_ENCODING => 'FAST_DIFF',
  IN_MEMORY => true
}, {
  NAME => 'capacity',
  VERSIONS => 5,
  TTL => 86400,
  COMPRESSION => 'LZ4',
  BLOOMFILTER => 'ROW',
  DATA_BLOCK_ENCODING => 'FAST_DIFF',
  IN_MEMORY => true
}, {
  NAME => 'status',
  VERSIONS => 3,
  TTL => 604800,        # 7 days for status changes
  COMPRESSION => 'LZ4',
  BLOOMFILTER => 'ROW'
}, {
  SPLITS => [
    '100-', '150-', '200-', '250-', '300-', '310-', '311-', '312-',
    '313-', '314-', '315-', '316-', '320-', '330-', '340-', '350-'
  ]
}

# ---------------------------------------------------------------------------
# Active Calls: tracks currently active calls for real-time monitoring
# Row key: call_id (UUID)
# Columns: call metadata, current tower, quality metrics
# TTL: auto-expires completed calls after 1 hour
# ---------------------------------------------------------------------------
create 'telco:active_calls', {
  NAME => 'call',
  VERSIONS => 1,
  TTL => 3600,          # 1 hour
  COMPRESSION => 'LZ4',
  BLOOMFILTER => 'ROW',
  DATA_BLOCK_ENCODING => 'FAST_DIFF',
  IN_MEMORY => true
}, {
  NAME => 'quality',
  VERSIONS => 1,
  TTL => 3600,
  COMPRESSION => 'LZ4',
  IN_MEMORY => true
}, {
  NAME => 'handoff_history',
  VERSIONS => 50,       # Track up to 50 handoffs per call
  TTL => 3600,
  COMPRESSION => 'LZ4'
}

# ---------------------------------------------------------------------------
# Tower Alerts: recent alerts and anomalies per tower
# Row key: tower_id#reverse_timestamp (for newest-first scan)
# ---------------------------------------------------------------------------
create 'telco:tower_alerts', {
  NAME => 'alert',
  VERSIONS => 1,
  TTL => 2592000,       # 30 days
  COMPRESSION => 'SNAPPY',
  BLOOMFILTER => 'ROW'
}, {
  NAME => 'resolution',
  VERSIONS => 3,
  TTL => 2592000,
  COMPRESSION => 'SNAPPY'
}

# ---------------------------------------------------------------------------
# Subscriber Mobility: per-subscriber recent handoff and tower history
# Row key: imsi_hash#reverse_timestamp
# Used for ping-pong detection and mobility pattern analysis
# ---------------------------------------------------------------------------
create 'telco:subscriber_mobility', {
  NAME => 'location',
  VERSIONS => 1,
  TTL => 86400,         # 24 hours
  COMPRESSION => 'LZ4',
  BLOOMFILTER => 'ROW',
  DATA_BLOCK_ENCODING => 'FAST_DIFF'
}, {
  NAME => 'handoff',
  VERSIONS => 1,
  TTL => 86400,
  COMPRESSION => 'LZ4',
  DATA_BLOCK_ENCODING => 'FAST_DIFF'
}

# ---------------------------------------------------------------------------
# Tower Performance History: daily scorecard snapshots
# Row key: tower_id#date (e.g., "310-260-1234-5678#2024-01-15")
# ---------------------------------------------------------------------------
create 'telco:tower_performance', {
  NAME => 'scores',
  VERSIONS => 1,
  TTL => 31536000,      # 1 year
  COMPRESSION => 'SNAPPY',
  BLOOMFILTER => 'ROW'
}, {
  NAME => 'metrics',
  VERSIONS => 1,
  TTL => 31536000,
  COMPRESSION => 'SNAPPY'
}

# List all tables
list 'telco:.*'

exit
