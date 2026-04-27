package com.telco.pipeline.spark.batch

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.slf4j.LoggerFactory

/**
 * Deep analysis of dropped calls to identify systemic network issues.
 *
 * Correlates dropped CDRs with tower signal data and handoff events
 * to classify dropped calls by root cause and identify patterns.
 *
 * Analysis dimensions:
 * - Spatial: which towers/sectors have highest drop rates
 * - Temporal: time-of-day and day-of-week patterns
 * - Mobility: drops correlated with handoff failures vs coverage holes
 * - Technology: drops by RAT type (LTE vs NR vs UMTS)
 * - Subscriber: repeat drop patterns indicating specific coverage gaps
 */
object DroppedCallAnalysis {

  private val LOG = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 4) {
      System.err.println(
        "Usage: DroppedCallAnalysis <cdr-path> <handoff-path> <signal-path> <output-path>")
      System.exit(1)
    }

    val cdrPath = args(0)
    val handoffPath = args(1)
    val signalPath = args(2)
    val outputPath = args(3)

    val spark = SparkSession.builder()
      .appName("TelcoDroppedCallAnalysis")
      .config("spark.sql.shuffle.partitions", "100")
      .config("spark.sql.adaptive.enabled", "true")
      .enableHiveSupport()
      .getOrCreate()

    import spark.implicits._

    try {
      val cdrDF = spark.read.parquet(cdrPath)
        .filter(col("is_dropped") === true)

      val handoffDF = spark.read.parquet(handoffPath)
      val signalDF = spark.read.parquet(signalPath)

      // 1. Spatial analysis: per-tower drop rates
      val spatialAnalysis = cdrDF.groupBy("originating_tower_id", "originating_sector_id")
        .agg(
          count("*").alias("dropped_count"),
          avg("avg_signal_strength_dbm").alias("avg_signal_at_drop"),
          avg("min_signal_strength_dbm").alias("avg_min_signal_at_drop"),
          avg("handoff_count").alias("avg_handoffs_before_drop"),
          countDistinct("caller_imsi_hash").alias("unique_subscribers_affected"),
          avg("call_duration_seconds").alias("avg_duration_before_drop"),
          // Distribution of termination causes
          count(when(col("termination_cause") === "RADIO_LINK_FAILURE", 1)).alias("rlf_drops"),
          count(when(col("termination_cause") === "HANDOFF_FAILURE", 1)).alias("handoff_drops"),
          count(when(col("termination_cause") === "CONGESTION", 1)).alias("congestion_drops"),
          count(when(col("termination_cause") === "RESOURCE_UNAVAILABLE", 1)).alias("resource_drops")
        )
        .withColumn("dominant_cause",
          when(col("rlf_drops") >= greatest(col("handoff_drops"), col("congestion_drops"), col("resource_drops")),
            "RADIO_LINK_FAILURE")
          .when(col("handoff_drops") >= greatest(col("congestion_drops"), col("resource_drops")),
            "HANDOFF_FAILURE")
          .when(col("congestion_drops") >= col("resource_drops"),
            "CONGESTION")
          .otherwise("RESOURCE_UNAVAILABLE")
        )

      spatialAnalysis.write.mode("overwrite")
        .parquet(s"$outputPath/spatial-analysis")

      // 2. Temporal patterns
      val temporalAnalysis = cdrDF
        .withColumn("hour_of_day",
          hour(from_unixtime(col("call_setup_timestamp_ms") / 1000)))
        .withColumn("day_of_week",
          dayofweek(from_unixtime(col("call_setup_timestamp_ms") / 1000)))
        .groupBy("hour_of_day", "day_of_week")
        .agg(
          count("*").alias("dropped_count"),
          avg("call_duration_seconds").alias("avg_duration"),
          avg("avg_signal_strength_dbm").alias("avg_signal"),
          countDistinct("originating_tower_id").alias("towers_affected")
        )

      temporalAnalysis.write.mode("overwrite")
        .parquet(s"$outputPath/temporal-analysis")

      // 3. Mobility correlation: drops related to handoff failures
      val mobilityCorrelation = cdrDF
        .filter(col("handoff_count") > 0)
        .join(
          handoffDF.filter(col("result") =!= "SUCCESS")
            .groupBy("call_id")
            .agg(
              count("*").alias("failed_handoffs"),
              collect_list("handoff_type").alias("failed_handoff_types"),
              collect_list("handoff_cause").alias("handoff_failure_causes"),
              avg("source_rsrp_dbm").alias("avg_rsrp_at_failure")
            ),
          Seq("call_id"), "left_outer"
        )
        .withColumn("drop_category",
          when(col("failed_handoffs") > 0, "HANDOFF_RELATED")
            .when(col("min_signal_strength_dbm") < -115, "COVERAGE_HOLE")
            .when(col("handoff_count") > 5, "HIGH_MOBILITY")
            .otherwise("OTHER")
        )

      mobilityCorrelation.write.mode("overwrite")
        .parquet(s"$outputPath/mobility-correlation")

      // 4. Summary statistics
      val summary = Map(
        "total_dropped_calls" -> cdrDF.count(),
        "unique_towers_with_drops" -> cdrDF.select("originating_tower_id").distinct().count(),
        "unique_subscribers_affected" -> cdrDF.select("caller_imsi_hash").distinct().count()
      )

      LOG.info("Dropped call analysis complete:")
      summary.foreach { case (k, v) => LOG.info(s"  $k: $v") }

    } finally {
      spark.stop()
    }
  }
}
