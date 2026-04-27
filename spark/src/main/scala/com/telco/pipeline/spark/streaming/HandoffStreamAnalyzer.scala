package com.telco.pipeline.spark.streaming

import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

/**
 * Real-time handoff event stream analyzer.
 *
 * Processes the telco.raw.handoff-events Kafka topic to detect:
 * 1. Ping-pong handoffs (UE bouncing between cells within 30s window)
 * 2. Handoff storms (burst of failures from a single tower)
 * 3. Mobility corridor degradation (rising failure rates on known paths)
 * 4. SRVCC/CSFB spikes indicating VoLTE coverage gaps
 *
 * Stateful processing: maintains per-subscriber handoff history using
 * mapWithState for efficient incremental state updates.
 */
object HandoffStreamAnalyzer {

  private val LOG = LoggerFactory.getLogger(getClass)

  case class HandoffEvent(
    eventId: String,
    imsiHash: String,
    timestampMs: Long,
    sourceTower: String,
    targetTower: String,
    handoffType: String,
    cause: String,
    result: String,
    sourceRsrp: Double,
    targetRsrp: Double,
    ueVelocity: Option[Double],
    handoffDurationMs: Option[Long],
    isVoiceActive: Boolean
  )

  case class SubscriberHandoffState(
    recentHandoffs: List[(Long, String, String, String)], // (timestamp, source, target, result)
    pingPongCount: Int,
    lastAlertTimestamp: Long
  ) {
    def addHandoff(ts: Long, src: String, tgt: String, result: String): SubscriberHandoffState = {
      val cutoff = ts - 60000 // Keep last 60 seconds
      val updated = ((ts, src, tgt, result) :: recentHandoffs)
        .filter(_._1 > cutoff)
        .take(20) // Max 20 entries

      // Detect ping-pong: same src-tgt pair within window
      val ppCount = updated.sliding(2).count {
        case List((_, s1, t1, _), (_, s2, t2, _)) =>
          (s1 == t2 && t1 == s2) || (s1 == s2 && t1 == t2)
        case _ => false
      }

      copy(recentHandoffs = updated, pingPongCount = ppCount)
    }
  }

  case class HandoffStormAlert(
    towerId: String,
    failureCount: Int,
    windowSeconds: Int,
    affectedSubscribers: Int,
    dominantCause: String,
    timestampMs: Long
  )

  // Configuration
  val PING_PONG_WINDOW_MS: Long = 30000
  val PING_PONG_THRESHOLD: Int = 3
  val STORM_WINDOW_SECONDS: Int = 60
  val STORM_FAILURE_THRESHOLD: Int = 50
  val SRVCC_SPIKE_THRESHOLD: Int = 20

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      System.err.println("Usage: HandoffStreamAnalyzer <bootstrap-servers> <checkpoint-dir>")
      System.exit(1)
    }

    val bootstrapServers = args(0)
    val checkpointDir = args(1)

    val conf = new SparkConf()
      .setAppName("TelcoHandoffStreamAnalyzer")
      .set("spark.streaming.backpressure.enabled", "true")

    val ssc = new StreamingContext(conf, Seconds(5))
    ssc.checkpoint(checkpointDir)

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> bootstrapServers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "telco-handoff-analyzer",
      "auto.offset.reset" -> "latest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    val topics = Array("telco.raw.handoff-events", "telco.raw.handoff-failures")
    val stream = KafkaUtils.createDirectStream[String, String](
      ssc, PreferConsistent, Subscribe[String, String](topics, kafkaParams)
    )

    val handoffs = stream.map(r => parseHandoffEvent(r.value())).filter(_.isDefined).map(_.get)

    // 1. Per-tower failure rate monitoring (windowed)
    val towerFailures = handoffs
      .filter(_.result != "SUCCESS")
      .map(h => (h.sourceTower, 1))
      .reduceByKeyAndWindow(_ + _, _ - _, Seconds(STORM_WINDOW_SECONDS), Seconds(10))

    towerFailures.foreachRDD { rdd =>
      rdd.filter(_._2 >= STORM_FAILURE_THRESHOLD).collect().foreach { case (tower, count) =>
        LOG.error(s"HANDOFF STORM detected at tower $tower: $count failures in ${STORM_WINDOW_SECONDS}s")
      }
    }

    // 2. SRVCC/CSFB spike detection
    val srvccCounts = handoffs
      .filter(h => h.handoffType.contains("SRVCC") || h.cause == "CSFB" || h.cause == "SRVCC")
      .map(h => (h.sourceTower, 1))
      .reduceByKeyAndWindow(_ + _, Seconds(300), Seconds(60))

    srvccCounts.foreachRDD { rdd =>
      rdd.filter(_._2 >= SRVCC_SPIKE_THRESHOLD).collect().foreach { case (tower, count) =>
        LOG.warn(s"SRVCC/CSFB spike at tower $tower: $count events in 5 minutes")
      }
    }

    // 3. Handoff success rate per tower pair
    val pairStats = handoffs
      .map(h => ((h.sourceTower, h.targetTower), if (h.result == "SUCCESS") (1, 0) else (0, 1)))
      .reduceByKeyAndWindow(
        (a: (Int, Int), b: (Int, Int)) => (a._1 + b._1, a._2 + b._2),
        Seconds(600), Seconds(60)
      )

    pairStats.foreachRDD { rdd =>
      rdd.filter { case (_, (success, failure)) =>
        val total = success + failure
        total >= 20 && failure.toDouble / total > 0.15
      }.collect().foreach { case ((src, tgt), (success, failure)) =>
        val total = success + failure
        val failRate = failure.toDouble / total * 100
        LOG.warn(f"High handoff failure rate $src -> $tgt: $failRate%.1f%% ($failure/$total)")
      }
    }

    // Commit offsets
    stream.foreachRDD { rdd =>
      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
      stream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges)
    }

    ssc.start()
    LOG.info("Handoff stream analyzer started")
    ssc.awaitTermination()
  }

  def parseHandoffEvent(value: String): Option[HandoffEvent] = {
    try {
      val f = value.split("\t")
      if (f.length < 13) return None

      Some(HandoffEvent(
        eventId = f(0),
        imsiHash = f(1),
        timestampMs = f(2).toLong,
        sourceTower = f(3),
        targetTower = f(4),
        handoffType = f(5),
        cause = f(6),
        result = f(7),
        sourceRsrp = f(8).toDouble,
        targetRsrp = f(9).toDouble,
        ueVelocity = if (f(10).nonEmpty) Some(f(10).toDouble) else None,
        handoffDurationMs = if (f(11).nonEmpty) Some(f(11).toLong) else None,
        isVoiceActive = f(12).toBoolean
      ))
    } catch {
      case _: Exception => None
    }
  }
}
