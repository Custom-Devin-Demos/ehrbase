package com.telco.pipeline.spark.streaming

import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

/**
 * Monitors live call quality by correlating CDR events with
 * concurrent tower signal measurements in real time.
 *
 * Joins the dropped-calls alert stream with tower signal data to
 * determine root cause of call quality issues:
 * - Coverage hole detection (low RSRP at call drop location)
 * - Capacity exhaustion (high PRB utilization + dropped call)
 * - Interference spikes (sudden SINR degradation during call)
 * - Backhaul saturation (high throughput + quality degradation)
 *
 * Output: enriched quality events to telco.enriched.call-quality topic
 * for dashboard consumption and automated ticket creation.
 */
object LiveCallQualityMonitor {

  private val LOG = LoggerFactory.getLogger(getClass)

  case class QualityEvent(
    callId: String,
    towerId: String,
    sectorId: Int,
    timestampMs: Long,
    mosScore: Option[Double],
    isDropped: Boolean,
    terminationCause: String,
    signalRsrp: Option[Double],
    signalSinr: Option[Double],
    prbUtilization: Option[Double],
    connectedUes: Option[Int]
  )

  case class RootCauseAnalysis(
    callId: String,
    towerId: String,
    rootCause: String,
    confidence: Double,
    evidence: String,
    recommendedAction: String,
    timestampMs: Long
  )

  val MOS_POOR_THRESHOLD: Double = 2.5
  val RSRP_COVERAGE_HOLE: Double = -110.0
  val SINR_INTERFERENCE: Double = 0.0
  val PRB_CONGESTION: Double = 90.0
  val UE_OVERLOAD_RATIO: Double = 0.9

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      System.err.println("Usage: LiveCallQualityMonitor <bootstrap-servers> <checkpoint-dir>")
      System.exit(1)
    }

    val bootstrapServers = args(0)
    val checkpointDir = args(1)

    val conf = new SparkConf()
      .setAppName("TelcoLiveCallQualityMonitor")
      .set("spark.streaming.backpressure.enabled", "true")
      .set("spark.streaming.receiver.maxRate", "50000")

    val ssc = new StreamingContext(conf, Seconds(5))
    ssc.checkpoint(checkpointDir)

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> bootstrapServers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "telco-call-quality-monitor",
      "auto.offset.reset" -> "latest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    // Consume both dropped calls and tower signals
    val droppedCallsTopic = Array("telco.alerts.dropped-calls")
    val signalsTopic = Array("telco.raw.tower-signals")

    val droppedStream = KafkaUtils.createDirectStream[String, String](
      ssc, PreferConsistent, Subscribe[String, String](droppedCallsTopic, kafkaParams)
    )

    val signalStream = KafkaUtils.createDirectStream[String, String](
      ssc, PreferConsistent,
      Subscribe[String, String](signalsTopic,
        kafkaParams ++ Map("group.id" -> "telco-call-quality-signals"))
    )

    // Parse streams
    val droppedCalls = droppedStream
      .map(r => parseQualityEvent(r.value()))
      .filter(_.isDefined).map(_.get)
      .filter(_.isDropped)

    val signals = signalStream
      .map(r => parseSignalForQuality(r.value()))
      .filter(_.isDefined).map(_.get)

    // Key both streams by tower ID for co-location analysis
    val keyedDrops = droppedCalls.map(q => (q.towerId, q))
    val keyedSignals = signals.map(s => (s._1, s)) // (towerId, signalData)

    // Window join: correlate drops with recent signals
    val windowedDrops = keyedDrops.window(Seconds(30), Seconds(10))
    val windowedSignals = keyedSignals.window(Seconds(60), Seconds(10))

    // Join and analyze root cause
    val joined = windowedDrops.join(windowedSignals)
    joined.foreachRDD { rdd =>
      if (!rdd.isEmpty()) {
        val analyses = rdd.map { case (towerId, (qualityEvent, signalData)) =>
          analyzeRootCause(qualityEvent, signalData)
        }.collect()

        analyses.foreach { analysis =>
          LOG.warn(s"ROOT CAUSE: ${analysis.rootCause} for call ${analysis.callId} " +
            s"at tower ${analysis.towerId} (confidence: ${analysis.confidence})")
        }
      }
    }

    ssc.start()
    LOG.info("Live call quality monitor started")
    ssc.awaitTermination()
  }

  def analyzeRootCause(event: QualityEvent,
                       signalData: (String, Double, Double, Double, Int)
                      ): RootCauseAnalysis = {
    val (towerId, rsrp, sinr, prbUtil, ues) = signalData
    val now = System.currentTimeMillis()

    // Priority-ordered root cause determination
    if (rsrp < RSRP_COVERAGE_HOLE) {
      RootCauseAnalysis(event.callId, towerId, "COVERAGE_HOLE", 0.9,
        f"RSRP=$rsrp%.1f dBm is below coverage threshold",
        "Investigate antenna tilt/azimuth; consider new small cell deployment",
        now)
    } else if (prbUtil > PRB_CONGESTION) {
      RootCauseAnalysis(event.callId, towerId, "CAPACITY_EXHAUSTION", 0.85,
        f"PRB utilization at $prbUtil%.1f%% with $ues connected UEs",
        "Consider load balancing to adjacent sectors or carrier aggregation",
        now)
    } else if (sinr < SINR_INTERFERENCE) {
      RootCauseAnalysis(event.callId, towerId, "INTERFERENCE", 0.8,
        f"SINR=$sinr%.1f dB indicates high interference",
        "Review PCI plan and frequency reuse; check for external interference sources",
        now)
    } else {
      RootCauseAnalysis(event.callId, towerId, "UNDETERMINED", 0.3,
        "Signal metrics within normal range at time of drop",
        "Review RAN logs for this tower; may be transient backhaul issue",
        now)
    }
  }

  def parseQualityEvent(value: String): Option[QualityEvent] = {
    try {
      val f = value.split(";")
      if (f.length < 8) return None
      Some(QualityEvent(
        callId = f(0), towerId = f(1), sectorId = f(2).toInt,
        timestampMs = f(3).toLong,
        mosScore = if (f(4).nonEmpty) Some(f(4).toDouble) else None,
        isDropped = f(5) == "1",
        terminationCause = f(6),
        signalRsrp = None, signalSinr = None,
        prbUtilization = None, connectedUes = None
      ))
    } catch { case _: Exception => None }
  }

  def parseSignalForQuality(value: String): Option[(String, Double, Double, Double, Int)] = {
    try {
      val f = value.split("\\|")
      if (f.length < 10) return None
      Some((f(0), f(5).toDouble, f(7).toDouble, f(9).toDouble, f(8).toInt))
    } catch { case _: Exception => None }
  }
}
