#!/bin/bash
# ============================================================================
# Sqoop Export: Network KPIs to Data Warehouse
# ============================================================================
# Exports computed daily KPIs from HDFS to the reporting database
# for consumption by BI tools and dashboards.
# ============================================================================

set -euo pipefail

REPORT_DATE="${1:?Usage: export_kpis.sh <report-date>}"
JDBC_URL="${DW_JDBC_URL:-jdbc:postgresql://telco-datawarehouse:5432/telco_reporting}"
JDBC_USER="${DW_USER:-sqoop_writer}"
JDBC_PASSWORD_FILE="${DW_PASSWORD_FILE:-/opt/telco/secrets/dw-password.txt}"
NUM_MAPPERS=2

echo "$(date '+%Y-%m-%d %H:%M:%S') Exporting KPIs for ${REPORT_DATE}..."

# Export daily network KPIs
sqoop export \
    --connect "${JDBC_URL}" \
    --username "${JDBC_USER}" \
    --password-file "file://${JDBC_PASSWORD_FILE}" \
    --table daily_network_kpis \
    --export-dir "/data/analytics/daily-network-kpis/dt=${REPORT_DATE}" \
    --input-fields-terminated-by '\t' \
    --num-mappers ${NUM_MAPPERS} \
    --update-key "report_date,region" \
    --update-mode allowinsert \
    -- --schema reporting

# Export tower scorecards
sqoop export \
    --connect "${JDBC_URL}" \
    --username "${JDBC_USER}" \
    --password-file "file://${JDBC_PASSWORD_FILE}" \
    --table tower_scorecards \
    --export-dir "/data/processed/tower-scorecards/dt=${REPORT_DATE}" \
    --input-fields-terminated-by '\t' \
    --num-mappers ${NUM_MAPPERS} \
    --update-key "tower_id,report_date" \
    --update-mode allowinsert \
    -- --schema reporting

# Export problem towers
sqoop export \
    --connect "${JDBC_URL}" \
    --username "${JDBC_USER}" \
    --password-file "file://${JDBC_PASSWORD_FILE}" \
    --table top_problem_towers \
    --export-dir "/data/analytics/problem-towers/dt=${REPORT_DATE}" \
    --input-fields-terminated-by '\t' \
    --num-mappers ${NUM_MAPPERS} \
    --update-key "tower_id,report_date" \
    --update-mode allowinsert \
    -- --schema reporting

echo "$(date '+%Y-%m-%d %H:%M:%S') KPI export complete for ${REPORT_DATE}."
