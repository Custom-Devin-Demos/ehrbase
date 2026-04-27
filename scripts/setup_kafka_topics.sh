#!/bin/bash
# ============================================================================
# Create Kafka topics for the Telco Data Pipeline
# ============================================================================

set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
PARTITIONS=12
REPLICATION=1  # Set to 3 for production

echo "Creating Kafka topics on ${BOOTSTRAP}..."

declare -A TOPICS=(
    ["telco.raw.tower-signals"]="${PARTITIONS}:168"    # 7 days retention
    ["telco.raw.handoff-events"]="${PARTITIONS}:168"
    ["telco.raw.handoff-failures"]="6:336"             # 14 days
    ["telco.raw.cdrs"]="${PARTITIONS}:168"
    ["telco.alerts.dropped-calls"]="6:72"              # 3 days
    ["telco.alerts.quality"]="6:72"
    ["telco.enriched.call-quality"]="6:168"
    ["telco.metrics.tower-state"]="6:24"               # 1 day
)

for TOPIC in "${!TOPICS[@]}"; do
    IFS=':' read -r PARTS RETENTION_HOURS <<< "${TOPICS[$TOPIC]}"
    RETENTION_MS=$((RETENTION_HOURS * 3600000))

    echo "  Creating ${TOPIC} (partitions=${PARTS}, retention=${RETENTION_HOURS}h)..."
    kafka-topics.sh --create \
        --bootstrap-server "${BOOTSTRAP}" \
        --topic "${TOPIC}" \
        --partitions "${PARTS}" \
        --replication-factor "${REPLICATION}" \
        --config retention.ms="${RETENTION_MS}" \
        --config compression.type=lz4 \
        --if-not-exists
done

echo "Listing all topics:"
kafka-topics.sh --list --bootstrap-server "${BOOTSTRAP}" | grep "^telco\."
echo "Done."
