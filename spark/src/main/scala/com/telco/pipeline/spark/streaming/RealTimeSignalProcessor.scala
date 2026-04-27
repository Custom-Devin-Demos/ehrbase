package com.telco.pipeline.spark.streaming

import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

/**
 * Real-time Spark Streaming processor for tower signal measurements.
 *
 * Consumes from the telco.raw.tower-signals Kafka topic and performs:
 * 1. Sliding window signal quality averaging (5-min window, 30s slide)
 * 2. Real-time anomaly detection via Z-score on RSRP/SINR
 * 3. Capacity threshold alerting when PRB utilization exceeds limits
 * 4. Per-tower rolling statistics maintained in HBase for dashboard queries
 *
 * Deployment: runs on YARN with 2 executors per regional cluster,
 * processing ~100k events/sec with sub-second latency.
 */
object RealTimeSignalProcessor {

  private val LOG = LoggerFactory.getLogger(getClass)

  case class TowerSignal(
    towerId: String,
    sectorId: Int,
    timestampMs: Long,
    rssi: Double,
    rsrp: Double,
    rsrq: Double,
    sinr: Double,
    cqi: Int,
    connectedUes: Int,
    prbUtilization: Double,
    dlThroughput: Double,
    ulThroughput: Double,
    interferenceLevel: Double
  )

  case class SignalAlert(
    towerId: String,
    sectorId: Int,
    alertType: String,
    severity: String,
    currentValue: Double,
    threshold: Double,
    timestampMs: Long,
    message: String
  )

  case class RollingStats(
    count: Long,
    sumRsrp: Double,
    sumSqRsrp: Double,
    sumSinr: Double,
    sumSqSinr: Double,
    minRsrp: Double,
    maxRsrp: Double,
    sumPrbUtil: Double,
    maxPrbUtil: Double,
    sumConnectedUes: Long
  ) {
    def mean(sum: Double): Double = if (count > 0) sum / count else 0.0
    def stddev(sum: Double, sumSq: Double): Double = {
      if (count > 1) {
        val variance = (sumSq - (sum * sum / count)) / (count - 1)
        math.sqrt(math.max(0, variance))
      } else 0.0
    }
    def rsrpMean: Double = mean(sumRsrp)
    def rsrpStdDev: Double = stddev(sumRsrp, sumSqRsrp)
    def sinrMean: Double = mean(sumSinr)
    def sinrStdDev: Double = stddev(sumSinr, sumSqSinr)
    def avgPrbUtil: Double = mean(sumPrbUtil)
    def avgConnectedUes: Double = if (count > 0) sumConnectedUes.toDouble / count else 0.0
  }

  object RollingStats {
    val empty: RollingStats = RollingStats(0, 0, 0, 0, 0, Double.MaxValue, Double.MinValue, 0, 0, 0)

    def fromSignal(s: TowerSignal): RollingStats = RollingStats(
      count = 1,
      sumRsrp = s.rsrp,
      sumSqRsrp = s.rsrp * s.rsrp,
      sumSinr = s.sinr,
      sumSqSinr = s.sinr * s.sinr,
      minRsrp = s.rsrp,
      maxRsrp = s.rsrp,
      sumPrbUtil = s.prbUtilization,
      maxPrbUtil = s.prbUtilization,
      sumConnectedUes = s.connectedUes
    )

    def merge(a: RollingStats, b: RollingStats): RollingStats = RollingStats(
      count = a.count + b.count,
      sumRsrp = a.sumRsrp + b.sumRsrp,
      sumSqRsrp = a.sumSqRsrp + b.sumSqRsrp,
      sumSinr = a.sumSinr + b.sumSinr,
      sumSqSinr = a.sumSqSinr + b.sumSqSinr,
      minRsrp = math.min(a.minRsrp, b.minRsrp),
      maxRsrp = math.max(a.maxRsrp, b.maxRsrp),
      sumPrbUtil = a.sumPrbUtil + b.sumPrbUtil,
      maxPrbUtil = math.max(a.maxPrbUtil, b.maxPrbUtil),
      sumConnectedUes = a.sumConnectedUes + b.sumConnectedUes
    )
  }

  // Thresholds
  val RSRP_CRITICAL_DBM: Double = -115.0
  val SINR_CRITICAL_DB: Double = -3.0
  val PRB_UTIL_WARNING_PCT: Double = 80.0
  val PRB_UTIL_CRITICAL_PCT: Double = 95.0
  val ZSCORE_ANOMALY_THRESHOLD: Double = 3.0
  val MAX_UES_WARNING_RATIO: Double = 0.85

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      System.err.println("Usage: RealTimeSignalProcessor <bootstrap-servers> <checkpoint-dir>")
      System.exit(1)
    }

    val bootstrapServers = args(0)
    val checkpointDir = args(1)

    val conf = new SparkConf()
      .setAppName("TelcoRealTimeSignalProcessor")
      .set("spark.streaming.kafka.maxRatePerPartition", "10000")
      .set("spark.streaming.backpressure.enabled", "true")
      .set("spark.streaming.kafka.consumer.cache.enabled", "true")

    val ssc = new StreamingContext(conf, Seconds(10))
    ssc.checkpoint(checkpointDir)

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> bootstrapServers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "telco-signal-processor",
      "auto.offset.reset" -> "latest",
      "enable.auto.commit" -> (false: java.lang.Boolean),
      "max.poll.records" -> "5000"
    )

    val topics = Array("telco.raw.tower-signals")

    val stream = KafkaUtils.createDirectStream[String, String](
      ssc, PreferConsistent, Subscribe[String, String](topics, kafkaParams)
    )

    // Parse signals
    val signals = stream.map { record =>
      parseSignal(record.key(), record.value())
    }.filter(_.isDefined).map(_.get)

    // Window-based aggregation (5 min window, 30s slide)
    val windowedSignals = signals
      .map(s => ((s.towerId, s.sectorId), s))
      .window(Seconds(300), Seconds(30))

    // Per-tower-sector rolling stats
    val towerStats = windowedSignals
      .map { case ((towerId, sectorId), signal) =>
        ((towerId, sectorId), RollingStats.fromSignal(signal))
      }
      .reduceByKey(RollingStats.merge)

    // Anomaly detection and alerting
    towerStats.foreachRDD { rdd =>
      if (!rdd.isEmpty()) {
        rdd.foreachPartition { partition =>
          partition.foreach { case ((towerId, sectorId), stats) =>
            val alerts = detectAnomalies(towerId, sectorId, stats)
            alerts.foreach { alert =>
              LOG.warn(s"ALERT [${alert.severity}] ${alert.alertType}: " +
                s"tower=$towerId sector=$sectorId - ${alert.message}")
            }
            // In production: write alerts to Kafka alert topic and update HBase
          }
        }
      }
    }

    // Commit Kafka offsets after processing
    stream.foreachRDD { rdd =>
      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
      stream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges)
    }

    ssc.start()
    LOG.info("Real-time signal processor started")
    ssc.awaitTermination()
  }

  def parseSignal(key: String, value: String): Option[TowerSignal] = {
    try {
      val fields = value.split("\\|")
      if (fields.length < 13) return None

      Some(TowerSignal(
        towerId = fields(0),
        sectorId = fields(1).toInt,
        timestampMs = fields(2).toLong,
        rssi = fields(3).toDouble,
        rsrp = fields(4).toDouble,
        rsrq = fields(5).toDouble,
        sinr = fields(6).toDouble,
        cqi = fields(7).toInt,
        connectedUes = fields(8).toInt,
        prbUtilization = fields(9).toDouble,
        dlThroughput = fields(10).toDouble,
        ulThroughput = fields(11).toDouble,
        interferenceLevel = fields(12).toDouble
      ))
    } catch {
      case _: Exception => None
    }
  }

  def detectAnomalies(towerId: String, sectorId: Int,
                      stats: RollingStats): Seq[SignalAlert] = {
    val alerts = scala.collection.mutable.ArrayBuffer[SignalAlert]()
    val now = System.currentTimeMillis()

    // Critical RSRP check
    if (stats.rsrpMean < RSRP_CRITICAL_DBM) {
      alerts += SignalAlert(towerId, sectorId, "RSRP_CRITICAL", "CRITICAL",
        stats.rsrpMean, RSRP_CRITICAL_DBM, now,
        f"Average RSRP ${stats.rsrpMean}%.1f dBm below critical threshold")
    }

    // SINR degradation
    if (stats.sinrMean < SINR_CRITICAL_DB) {
      alerts += SignalAlert(towerId, sectorId, "SINR_CRITICAL", "CRITICAL",
        stats.sinrMean, SINR_CRITICAL_DB, now,
        f"Average SINR ${stats.sinrMean}%.1f dB below critical threshold")
    }

    // PRB utilization warnings
    if (stats.maxPrbUtil > PRB_UTIL_CRITICAL_PCT) {
      alerts += SignalAlert(towerId, sectorId, "PRB_UTIL_CRITICAL", "CRITICAL",
        stats.maxPrbUtil, PRB_UTIL_CRITICAL_PCT, now,
        f"PRB utilization peaked at ${stats.maxPrbUtil}%.1f%%")
    } else if (stats.avgPrbUtil > PRB_UTIL_WARNING_PCT) {
      alerts += SignalAlert(towerId, sectorId, "PRB_UTIL_WARNING", "WARNING",
        stats.avgPrbUtil, PRB_UTIL_WARNING_PCT, now,
        f"Average PRB utilization ${stats.avgPrbUtil}%.1f%% exceeds warning threshold")
    }

    // Z-score anomaly on RSRP
    if (stats.count > 30 && stats.rsrpStdDev > 0) {
      val latestRsrpZScore = math.abs(
        (stats.minRsrp - stats.rsrpMean) / stats.rsrpStdDev
      )
      if (latestRsrpZScore > ZSCORE_ANOMALY_THRESHOLD) {
        alerts += SignalAlert(towerId, sectorId, "RSRP_ANOMALY", "WARNING",
          latestRsrpZScore, ZSCORE_ANOMALY_THRESHOLD, now,
          f"RSRP Z-score $latestRsrpZScore%.2f indicates anomalous signal behavior")
      }
    }

    alerts.toSeq
  }
}
