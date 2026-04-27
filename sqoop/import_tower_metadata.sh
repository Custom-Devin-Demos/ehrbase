#!/bin/bash
# ============================================================================
# Sqoop Import: Tower Metadata from Network Inventory RDBMS
# ============================================================================
# Imports tower configuration and metadata from the operational database
# into HDFS Parquet format for joining with telemetry data.
#
# Schedule: daily incremental import based on last_modified_date
# ============================================================================

set -euo pipefail

JDBC_URL="${TOWER_DB_JDBC_URL:-jdbc:postgresql://tower-inventory-db:5432/network_inventory}"
JDBC_USER="${TOWER_DB_USER:-sqoop_reader}"
JDBC_PASSWORD_FILE="${TOWER_DB_PASSWORD_FILE:-/opt/telco/secrets/tower-db-password.txt}"
TARGET_DIR="/data/dimension/tower-metadata"
STAGING_DIR="/data/staging/tower-metadata"
NUM_MAPPERS=4
LAST_VALUE_FILE="/opt/telco/sqoop/tower-metadata-lastvalue.txt"

echo "$(date '+%Y-%m-%d %H:%M:%S') Starting tower metadata import..."

# Determine last import timestamp for incremental mode
INCREMENTAL_MODE="--incremental lastmodified"
LAST_VALUE=""
if [[ -f "${LAST_VALUE_FILE}" ]]; then
    LAST_VALUE=$(cat "${LAST_VALUE_FILE}")
    echo "Incremental import since: ${LAST_VALUE}"
    INCREMENTAL_MODE="${INCREMENTAL_MODE} --check-column last_modified_date --last-value ${LAST_VALUE}"
else
    echo "Full import (no previous checkpoint)"
    INCREMENTAL_MODE=""
fi

# Run Sqoop import
sqoop import \
    --connect "${JDBC_URL}" \
    --username "${JDBC_USER}" \
    --password-file "file://${JDBC_PASSWORD_FILE}" \
    --table tower_inventory \
    --columns "tower_id,site_id,site_name,mcc,mnc,lac,cell_id,enodeb_id,latitude,longitude,elevation_m,tower_height_m,tower_type,vendor,hardware_model,firmware_version,backhaul_type,backhaul_capacity_mbps,power_source,battery_backup_hours,install_date,last_maintenance_date,region,market,is_active,indoor_outdoor,morphology,last_modified_date" \
    --target-dir "${STAGING_DIR}" \
    --delete-target-dir \
    --as-parquetfile \
    --compression-codec snappy \
    --num-mappers ${NUM_MAPPERS} \
    --split-by tower_id \
    --null-string '' \
    --null-non-string '' \
    ${INCREMENTAL_MODE} \
    -- --schema network

SQOOP_EXIT=$?

if [[ ${SQOOP_EXIT} -eq 0 ]]; then
    echo "Sqoop import succeeded. Moving data to final location..."

    # Atomic swap: move staging to target
    hdfs dfs -rm -r -f "${TARGET_DIR}.old" 2>/dev/null || true
    hdfs dfs -test -d "${TARGET_DIR}" && hdfs dfs -mv "${TARGET_DIR}" "${TARGET_DIR}.old"
    hdfs dfs -mv "${STAGING_DIR}" "${TARGET_DIR}"
    hdfs dfs -rm -r -f "${TARGET_DIR}.old" 2>/dev/null || true

    # Update last value checkpoint
    date -u '+%Y-%m-%d %H:%M:%S' > "${LAST_VALUE_FILE}"

    echo "$(date '+%Y-%m-%d %H:%M:%S') Tower metadata import complete."
else
    echo "ERROR: Sqoop import failed with exit code ${SQOOP_EXIT}" >&2
    exit ${SQOOP_EXIT}
fi
