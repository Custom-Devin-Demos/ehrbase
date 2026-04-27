# Telco Data Pipeline

A production-grade Hadoop-based data pipeline for processing telecommunications call telemetry — tower signal measurements, handoff events, and call detail records (CDRs).

## Architecture

```
Tower Signals ─→ Flume/Kafka ─→ Spark Streaming ─→ HBase (real-time)
Handoff Events ─→ Kafka       ─→ MapReduce       ─→ Hive  (batch)
CDRs           ─→ Flume/Kafka ─→ Pig ETL         ─→ Spark ML (analytics)
Tower Metadata ─→ Sqoop       ─→ HDFS Parquet    ─→ Sqoop Export (DW)
                                                    ↕
                                              Oozie (orchestration)
```

See [docs/architecture.md](docs/architecture.md) for the full architecture guide.

## Project Structure

```
telco-data-pipeline/
├── schema/
│   ├── avro/                  # Avro schemas (tower_signal, handoff, CDR, metadata)
│   └── hive/                  # Hive DDL (raw, processed, analytics tables)
├── ingestion/                 # Kafka producers, Flume interceptors
├── mapreduce/                 # MapReduce jobs (signal agg, handoff, CDR)
├── spark/                     # Spark Streaming, batch analytics, ML models
├── hive/
│   ├── udfs/                  # Custom UDFs (signal classifier, geo distance)
│   └── queries/               # Analytical HQL queries
├── pig/                       # Pig ETL scripts (signal ETL, CDR enrichment)
├── hbase/
│   ├── schema/                # HBase table schemas (Ruby script)
│   └── src/                   # DAOs, coprocessors
├── oozie/
│   ├── workflows/             # Signal aggregation, CDR processing
│   ├── coordinators/          # Daily scheduling
│   └── bundles/               # Pipeline bundle
├── sqoop/                     # Import/export scripts
├── config/                    # Hadoop, HBase, Hive, Kafka, Flume configs
├── docker/                    # docker-compose for local dev environment
├── scripts/                   # Test data generator, setup scripts
└── docs/                      # Architecture documentation
```

## Components

### Ingestion
- **Kafka Producers**: `TowerSignalProducer`, `HandoffEventProducer`, `CDRProducer` with idempotent writes, LZ4 compression, and per-partition ordering
- **Flume Interceptors**: `TowerSignalInterceptor` (signal validation, dedup, quality classification), `CDREventInterceptor` (call classification, priority routing)
- **Avro Serialization**: Confluent Schema Registry-compatible wire format

### MapReduce
- **Signal Quality Aggregation**: 15-minute bucketed tower-sector statistics with combiners
- **Handoff Pattern Analysis**: tower-pair success rates, ping-pong detection, cause analysis
- **CDR Processing**: per-tower call statistics with dropped call alerting
- Custom `WritableComparable` keys and `TelcoPartitioner` for domain-specific data distribution

### Spark
- **Streaming**: Real-time signal monitoring (Z-score anomaly detection), handoff storm detection, live call quality correlation
- **Batch**: Tower performance scorecards, dropped call root cause analysis, network coverage reports
- **ML**: Handoff failure prediction (Gradient Boosted Trees with 5-fold CV), tower anomaly detection (K-Means clustering)

### Storage
- **Hive**: 3-zone warehouse (raw/processed/analytics) with Avro→Parquet pipeline, custom UDFs for signal classification and geospatial calculations
- **HBase**: Real-time tower state (10-version history), active call tracking, subscriber mobility patterns
- **HDFS**: Date/region partitioned with Snappy compression

### Orchestration
- **Oozie Workflows**: Multi-step pipelines with fork/join parallelism and data availability checks
- **Coordinators**: Daily scheduling with 6-hour timeout, `LAST_ONLY` execution
- **Bundles**: Complete pipeline lifecycle management

### Data Integration
- **Sqoop Import**: Tower metadata from RDBMS with incremental updates
- **Sqoop Export**: KPIs and scorecards to reporting data warehouse

## Quick Start

### Prerequisites
- Docker and Docker Compose
- Java 8+ and Maven 3.6+
- Python 3.8+ (for test data generator)

### Local Development

```bash
# Start infrastructure
cd docker && docker-compose up -d

# Build all modules
mvn clean package -DskipTests

# Create Kafka topics
bash scripts/setup_kafka_topics.sh

# Create HDFS directories
bash scripts/setup_hdfs_dirs.sh

# Create HBase tables
hbase shell < hbase/schema/create_tables.rb

# Create Hive tables
hive -f schema/hive/create_raw_tables.hql
hive -f schema/hive/create_processed_tables.hql
hive -f schema/hive/create_aggregated_tables.hql

# Generate test data
python scripts/generate_test_data.py --towers 50 --hours 24 --output-dir test-data
```

### Running Jobs

```bash
# MapReduce signal aggregation
hadoop jar mapreduce/target/telco-mapreduce-*.jar \
  com.telco.pipeline.mapreduce.signal.SignalQualityDriver \
  /data/raw/tower-signals/dt=2024-01-15 \
  /data/processed/signal-quality/dt=2024-01-15

# Spark streaming (real-time signal monitoring)
spark-submit --class com.telco.pipeline.spark.streaming.RealTimeSignalProcessor \
  --master yarn --deploy-mode cluster \
  spark/target/telco-spark-*.jar \
  kafka-broker-0:9092 /checkpoint/signal-processor

# Spark batch (tower scorecards)
spark-submit --class com.telco.pipeline.spark.batch.TowerPerformanceAnalyzer \
  --master yarn --deploy-mode cluster \
  spark/target/telco-spark-*.jar \
  /data/processed/signal-quality/dt=2024-01-15 \
  /data/processed/cdr-tower-stats/dt=2024-01-15 \
  /data/processed/tower-scorecards/dt=2024-01-15

# Pig ETL
pig -param INPUT=/data/raw/tower-signals/dt=2024-01-15 \
    -param TOWER_META=/data/dimension/tower-metadata \
    -param OUTPUT=/data/processed/signal-quality/dt=2024-01-15 \
    pig/tower_signal_etl.pig
```

## Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Distributed Storage | Apache Hadoop HDFS | 3.3.6 |
| Resource Management | Apache YARN | 3.3.6 |
| Batch Processing | Apache MapReduce | 3.3.6 |
| Stream Processing | Apache Spark Streaming | 3.5.0 |
| Batch Analytics | Apache Spark SQL | 3.5.0 |
| Machine Learning | Apache Spark MLlib | 3.5.0 |
| Message Queue | Apache Kafka | 3.6.1 |
| Data Warehouse | Apache Hive | 3.1.3 |
| Real-time Store | Apache HBase | 2.5.7 |
| ETL Scripting | Apache Pig | 0.17.0 |
| Log Aggregation | Apache Flume | 1.11.0 |
| RDBMS Integration | Apache Sqoop | 1.4.7 |
| Workflow Engine | Apache Oozie | 5.2.1 |
| Schema Format | Apache Avro | 1.11.3 |
| Columnar Format | Apache Parquet | 1.13.1 |
| Build Tool | Apache Maven | 3.6+ |
| JVM Language | Scala | 2.12.18 |

## Configuration

The `config/` directory contains sample configurations for all components. Key settings:

- **Kafka**: 12 partitions, LZ4 compression, 7-day retention
- **HDFS**: 3x replication, 128MB block size
- **HBase**: LZ4 compression, row bloom filters, in-memory for tower state
- **Hive**: Tez execution engine, vectorized execution, Snappy compression

## License

Proprietary — Internal Use Only
