package com.telco.pipeline.spark.batch

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.types._
import org.slf4j.LoggerFactory

/**
 * Batch Spark job for comprehensive tower performance analysis.
 *
 * Runs daily to produce:
 * 1. Tower performance scorecards (composite KPI scores)
 * 2. Sector-level capacity utilization trends
 * 3. Signal quality heatmaps (spatial analysis)
 * 4. Under/over-performing tower identification vs market peers
 * 5. Capacity planning projections based on growth trends
 *
 * Input: processed signal quality and CDR data from HDFS
 * Output: Parquet files in the analytics zone + HBase tower scores
 */
object TowerPerformanceAnalyzer {

  private val LOG = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 3) {
      System.err.println(
        "Usage: TowerPerformanceAnalyzer <signal-data-path> <cdr-data-path> <output-path>")
      System.exit(1)
    }

    val signalPath = args(0)
    val cdrPath = args(1)
    val outputPath = args(2)

    val spark = SparkSession.builder()
      .appName("TelcoTowerPerformanceAnalyzer")
      .config("spark.sql.shuffle.partitions", "200")
      .config("spark.sql.adaptive.enabled", "true")
      .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
      .enableHiveSupport()
      .getOrCreate()

    import spark.implicits._

    try {
      val signalDF = loadSignalData(spark, signalPath)
      val cdrDF = loadCDRData(spark, cdrPath)

      // 1. Tower-level signal performance scores
      val signalScores = computeSignalScores(signalDF)
      signalScores.write.mode("overwrite")
        .parquet(s"$outputPath/signal-scores")

      // 2. Sector capacity analysis
      val capacityAnalysis = analyzeCapacity(signalDF)
      capacityAnalysis.write.mode("overwrite")
        .parquet(s"$outputPath/capacity-analysis")

      // 3. Combined tower scorecard
      val towerScorecard = buildTowerScorecard(signalScores, cdrDF)
      towerScorecard.write.mode("overwrite")
        .parquet(s"$outputPath/tower-scorecards")

      // 4. Peer comparison (rank within market)
      val peerComparison = computePeerComparison(towerScorecard)
      peerComparison.write.mode("overwrite")
        .parquet(s"$outputPath/peer-comparison")

      LOG.info("Tower performance analysis complete")

    } finally {
      spark.stop()
    }
  }

  def loadSignalData(spark: SparkSession, path: String): DataFrame = {
    val schema = StructType(Seq(
      StructField("tower_id", StringType),
      StructField("sector_id", IntegerType),
      StructField("time_bucket", LongType),
      StructField("sample_count", IntegerType),
      StructField("avg_rssi", DoubleType),
      StructField("avg_rsrp", DoubleType),
      StructField("avg_sinr", DoubleType),
      StructField("min_rssi", DoubleType),
      StructField("max_rssi", DoubleType),
      StructField("min_rsrp", DoubleType),
      StructField("max_rsrp", DoubleType),
      StructField("avg_prb_util", DoubleType),
      StructField("avg_dl_throughput", DoubleType),
      StructField("avg_ul_throughput", DoubleType),
      StructField("avg_connected_ues", DoubleType),
      StructField("poor_quality_ratio", DoubleType)
    ))

    spark.read.option("delimiter", "\t").schema(schema).csv(path)
  }

  def loadCDRData(spark: SparkSession, path: String): DataFrame = {
    val schema = StructType(Seq(
      StructField("tower_id", StringType),
      StructField("total_calls", IntegerType),
      StructField("dropped_calls", IntegerType),
      StructField("dropped_call_rate", DoubleType),
      StructField("zero_duration", IntegerType),
      StructField("avg_duration", DoubleType),
      StructField("avg_handoffs", DoubleType),
      StructField("avg_rssi", DoubleType),
      StructField("min_rssi", DoubleType),
      StructField("avg_sinr", DoubleType),
      StructField("avg_mos", DoubleType),
      StructField("dominant_term_cause", StringType),
      StructField("dominant_call_type", StringType)
    ))

    spark.read.option("delimiter", "\t").schema(schema).csv(path)
  }

  def computeSignalScores(signalDF: DataFrame): DataFrame = {
    signalDF.groupBy("tower_id")
      .agg(
        avg("avg_rsrp").alias("overall_avg_rsrp"),
        avg("avg_sinr").alias("overall_avg_sinr"),
        avg("avg_prb_util").alias("overall_avg_prb_util"),
        avg("avg_dl_throughput").alias("overall_avg_dl_throughput"),
        avg("avg_ul_throughput").alias("overall_avg_ul_throughput"),
        max("avg_connected_ues").alias("peak_connected_ues"),
        avg("poor_quality_ratio").alias("avg_poor_quality_ratio"),
        count("*").alias("total_buckets"),
        // Signal stability: standard deviation of RSRP across time
        stddev("avg_rsrp").alias("rsrp_stability"),
        // Utilization variability
        stddev("avg_prb_util").alias("prb_util_variability"),
        // Coverage consistency
        min("min_rsrp").alias("worst_rsrp"),
        max("max_rsrp").alias("best_rsrp")
      )
      .withColumn("signal_score",
        // Weighted composite score (0-100)
        greatest(lit(0), least(lit(100),
          // RSRP component (40% weight): scale -120 to -70 dBm → 0 to 100
          (col("overall_avg_rsrp") + lit(120)) / lit(50) * lit(40) +
          // SINR component (30% weight): scale -5 to 25 dB → 0 to 100
          (col("overall_avg_sinr") + lit(5)) / lit(30) * lit(30) +
          // Inverse poor quality (20% weight)
          (lit(1) - col("avg_poor_quality_ratio")) * lit(20) +
          // Stability bonus (10% weight)
          greatest(lit(0), lit(10) - col("rsrp_stability"))
        ))
      )
  }

  def analyzeCapacity(signalDF: DataFrame): DataFrame = {
    val hourWindow = Window.partitionBy("tower_id", "sector_id")
      .orderBy("time_bucket")
      .rowsBetween(-12, 0) // ~3 hour rolling window with 15-min buckets

    signalDF
      .withColumn("rolling_avg_prb", avg("avg_prb_util").over(hourWindow))
      .withColumn("rolling_max_ues", max("avg_connected_ues").over(hourWindow))
      .withColumn("capacity_status",
        when(col("avg_prb_util") > 95, "CRITICAL")
          .when(col("avg_prb_util") > 80, "WARNING")
          .when(col("avg_prb_util") > 60, "MODERATE")
          .otherwise("HEALTHY")
      )
      .withColumn("hour_of_day",
        (col("time_bucket") / 3600000 % 24).cast(IntegerType))
      .groupBy("tower_id", "sector_id", "hour_of_day")
      .agg(
        avg("avg_prb_util").alias("avg_hourly_prb_util"),
        max("avg_prb_util").alias("peak_hourly_prb_util"),
        avg("avg_connected_ues").alias("avg_hourly_ues"),
        max("avg_connected_ues").alias("peak_hourly_ues"),
        avg("avg_dl_throughput").alias("avg_hourly_dl"),
        avg("avg_ul_throughput").alias("avg_hourly_ul"),
        count(when(col("capacity_status") === "CRITICAL", 1)).alias("critical_periods"),
        count("*").alias("total_periods")
      )
  }

  def buildTowerScorecard(signalScores: DataFrame, cdrDF: DataFrame): DataFrame = {
    signalScores.join(cdrDF, Seq("tower_id"), "left_outer")
      .withColumn("call_quality_score",
        greatest(lit(0), least(lit(100),
          // Inverse dropped call rate (50% weight)
          (lit(1) - coalesce(col("dropped_call_rate"), lit(0))) * lit(50) +
          // MOS score component (30% weight): scale 1-5 → 0-100
          coalesce((col("avg_mos") - lit(1)) / lit(4), lit(0.5)) * lit(30) +
          // Call volume health (20% weight): penalize very low volume
          least(lit(20), coalesce(col("total_calls"), lit(0)) / lit(50) * lit(20))
        ))
      )
      .withColumn("composite_score",
        col("signal_score") * lit(0.6) + col("call_quality_score") * lit(0.4)
      )
      .withColumn("performance_tier",
        when(col("composite_score") >= 85, "TIER_1")
          .when(col("composite_score") >= 70, "TIER_2")
          .when(col("composite_score") >= 55, "TIER_3")
          .when(col("composite_score") >= 40, "TIER_4")
          .otherwise("TIER_5_REMEDIATION")
      )
  }

  def computePeerComparison(scorecardDF: DataFrame): DataFrame = {
    val marketWindow = Window.partitionBy()
      .orderBy(col("composite_score").desc)

    scorecardDF
      .withColumn("network_rank", row_number().over(marketWindow))
      .withColumn("network_percentile",
        percent_rank().over(marketWindow) * lit(100))
      .withColumn("vs_network_avg",
        col("composite_score") - avg("composite_score").over(Window.partitionBy()))
  }
}
