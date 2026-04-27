#!/bin/bash
# ============================================================================
# Create HDFS directory structure for the Telco Data Pipeline
# ============================================================================

set -euo pipefail

echo "Creating HDFS directory structure..."

# Data zones
DIRS=(
    "/data/raw/tower-signals"
    "/data/raw/handoff-events"
    "/data/raw/cdr"
    "/data/backup/tower-signals"
    "/data/staging/tower-metadata"
    "/data/dimension/tower-metadata"
    "/data/processed/signal-quality"
    "/data/processed/handoff-analysis"
    "/data/processed/cdr-tower-stats"
    "/data/processed/enriched-cdrs"
    "/data/processed/tower-scorecards"
    "/data/analytics/daily-network-kpis"
    "/data/analytics/dropped-call-analysis"
    "/data/analytics/coverage-report"
    "/data/analytics/problem-towers"
    "/data/ml/handoff-training"
    "/data/ml/anomaly-detection"
    "/data/ml/models"
    "/data/warehouse"
    "/schema/avro"
    "/opt/telco/lib"
    "/opt/telco/oozie/workflows"
    "/opt/telco/oozie/coordinators"
    "/opt/telco/hive/queries"
    "/opt/telco/pig"
)

for DIR in "${DIRS[@]}"; do
    hdfs dfs -mkdir -p "${DIR}"
    echo "  Created ${DIR}"
done

# Set permissions
hdfs dfs -chmod -R 755 /data
hdfs dfs -chmod -R 755 /opt/telco
hdfs dfs -chown -R telco:telco /data

echo "HDFS directory structure created."
