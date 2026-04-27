# Telco Data Pipeline Architecture

## Overview

This pipeline processes call telemetry data from a telecommunications network, ingesting tower signal measurements, handoff events, and call detail records (CDRs) through a multi-layered Hadoop-based architecture.

## Architecture Diagram

```
                    ┌─────────────────────────────────────────────────────┐
                    │              Data Sources                           │
                    │  Tower Signals │ Handoff Events │ CDRs │ Metadata  │
                    └───────┬────────┴───────┬────────┴──┬───┴─────┬────┘
                            │                │           │         │
                    ┌───────▼────────┐ ┌─────▼─────┐    │         │
                    │  Flume Agents  │ │   Kafka    │    │    ┌────▼────┐
                    │  (Interceptors)│ │ Producers  │    │    │  Sqoop  │
                    └───────┬────────┘ └─────┬──────┘    │    │ Import  │
                            │                │           │    └────┬────┘
                    ┌───────▼────────────────▼───────────▼─────────▼────┐
                    │                  Apache Kafka                      │
                    │  telco.raw.tower-signals  │ telco.raw.handoff-*   │
                    │  telco.raw.cdrs           │ telco.alerts.*        │
                    └────────┬──────────────────┬──────────────────┬────┘
                             │                  │                  │
                    ┌────────▼────────┐ ┌───────▼───────┐ ┌───────▼───────┐
                    │  Spark Streaming│ │   MapReduce   │ │    HDFS Raw   │
                    │  (Real-time)    │ │   (Batch)     │ │    Zone       │
                    │  - Signal QoS   │ │  - Signal Agg │ │               │
                    │  - Handoff Det  │ │  - Handoff    │ │               │
                    │  - Call Quality │ │  - CDR Stats  │ │               │
                    └────────┬────────┘ └───────┬───────┘ └───────┬───────┘
                             │                  │                  │
                    ┌────────▼────────┐ ┌───────▼───────┐ ┌───────▼───────┐
                    │     HBase       │ │  Pig ETL      │ │  Spark Batch  │
                    │  (Real-time     │ │  (Enrichment) │ │  (Analytics)  │
                    │   state store)  │ │               │ │  - Perf Scores│
                    │  - Tower State  │ │               │ │  - Coverage   │
                    │  - Active Calls │ │               │ │  - Drop Anlys │
                    │  - Alerts       │ │               │ │               │
                    └────────┬────────┘ └───────┬───────┘ └───────┬───────┘
                             │                  │                  │
                    ┌────────▼──────────────────▼──────────────────▼────┐
                    │               Hive Data Warehouse                  │
                    │  Raw Zone │ Processed Zone │ Analytics Zone        │
                    │                                                    │
                    │  UDFs: SignalStrengthClassifier, GeoDistance,      │
                    │        HandoffSuccessRate                          │
                    └────────┬──────────────────────────────────────┬───┘
                             │                                      │
                    ┌────────▼────────┐              ┌──────────────▼───┐
                    │  Sqoop Export   │              │  Spark ML         │
                    │  → Data         │              │  - Handoff Pred.  │
                    │    Warehouse    │              │  - Anomaly Det.   │
                    └─────────────────┘              └──────────────────┘
                             │
                    ┌────────▼─────────────────────────────────────────┐
                    │           Oozie Orchestration                     │
                    │  Bundle → Coordinator → Workflows (daily)        │
                    └──────────────────────────────────────────────────┘
```

## Data Flow

### Ingestion Layer
1. **Tower Signals**: syslog → Flume (TowerSignalInterceptor) → Kafka → HDFS
2. **Handoff Events**: MME/AMF → Kafka (HandoffEventProducer) → HDFS
3. **CDRs**: MSC/IMS → Flume (CDREventInterceptor) → Kafka → HDFS
4. **Tower Metadata**: RDBMS → Sqoop → HDFS (Parquet)

### Processing Layer
- **Real-time**: Spark Streaming from Kafka (10s micro-batches)
- **Batch**: MapReduce jobs for daily aggregation (15-min signal buckets)
- **ETL**: Pig scripts for CDR enrichment and handoff correlation

### Storage Layer
- **HBase**: real-time tower state, active calls, alerts (TTL-managed)
- **Hive**: 3-zone warehouse (raw → processed → analytics) with Parquet/Snappy
- **HDFS**: underlying storage with date/region partitioning

### Analytics Layer
- **Hive Queries**: hourly signal quality, dropped call reports, coverage gaps
- **Spark Batch**: tower performance scorecards, peer comparison, coverage maps
- **Spark ML**: handoff failure prediction (GBT), anomaly detection (K-Means)

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Serialization | Avro + Schema Registry | Schema evolution for telco format changes |
| Compression | LZ4 (streaming), Snappy (batch) | LZ4 for latency, Snappy for ratio |
| Partitioning | Date + Region | Query locality and lifecycle management |
| Columnar Storage | Parquet | Efficient analytics queries |
| Time Bucketing | 15-minute intervals | Balances granularity vs. volume |
| HBase Row Key | tower_id#sector_id | Even distribution, range scans |
| ML Retraining | Daily on 30-day window | Captures seasonal patterns |

## Telco Domain Metrics

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| RSRP | Reference Signal Received Power | < -115 dBm |
| SINR | Signal-to-Interference-plus-Noise Ratio | < -3 dB |
| CQI | Channel Quality Indicator | < 4 |
| PRB Utilization | Physical Resource Block usage | > 80% warning, > 95% critical |
| MOS | Mean Opinion Score (voice quality) | < 2.5 |
| Dropped Call Rate | Percentage of abnormally terminated calls | > 2% |
| Handoff Success Rate | Percentage of successful handoffs | < 85% |
