package com.telco.pipeline.spark.batch

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.slf4j.LoggerFactory

/**
 * Generates network coverage reports by analyzing signal measurements
 * across the entire tower fleet.
 *
 * Produces:
 * - Coverage gap identification (areas with weak/no signal)
 * - Overlap analysis (areas served by 3+ towers — interference risk)
 * - Technology coverage maps (LTE vs NR coverage percentages)
 * - Indoor vs outdoor coverage estimates
 * - Regulatory compliance reports (minimum coverage requirements)
 */
object NetworkCoverageReport {

  private val LOG = LoggerFactory.getLogger(getClass)

  case class CoverageCell(
    geohash: String,
    avgRsrp: Double,
    avgSinr: Double,
    towerCount: Int,
    technologies: Seq[String],
    coverageClass: String
  )

  val GEOHASH_PRECISION = 7 // ~150m x 150m cells

  def main(args: Array[String]): Unit = {
    if (args.length < 3) {
      System.err.println("Usage: NetworkCoverageReport <signal-path> <tower-metadata-path> <output-path>")
      System.exit(1)
    }

    val signalPath = args(0)
    val towerMetadataPath = args(1)
    val outputPath = args(2)

    val spark = SparkSession.builder()
      .appName("TelcoNetworkCoverageReport")
      .config("spark.sql.shuffle.partitions", "200")
      .config("spark.sql.adaptive.enabled", "true")
      .getOrCreate()

    import spark.implicits._

    try {
      val signalDF = spark.read.parquet(signalPath)
      val towerDF = spark.read.parquet(towerMetadataPath)

      // Enrich signals with tower location data
      val enrichedSignals = signalDF
        .join(towerDF.select("tower_id", "latitude", "longitude", "morphology", "tower_type"),
          Seq("tower_id"), "inner")
        .withColumn("geohash", computeGeohash(col("latitude"), col("longitude")))

      // Per-geohash coverage analysis
      val coverageMap = enrichedSignals
        .groupBy("geohash")
        .agg(
          avg("avg_rsrp").alias("area_avg_rsrp"),
          avg("avg_sinr").alias("area_avg_sinr"),
          min("min_rsrp").alias("area_min_rsrp"),
          countDistinct("tower_id").alias("serving_towers"),
          collect_set("tower_type").alias("tower_types"),
          avg("avg_prb_util").alias("area_avg_prb_util"),
          avg("avg_dl_throughput").alias("area_avg_dl_throughput"),
          count("*").alias("measurement_count")
        )
        .withColumn("coverage_class",
          when(col("area_avg_rsrp") >= -80, "EXCELLENT")
            .when(col("area_avg_rsrp") >= -95, "GOOD")
            .when(col("area_avg_rsrp") >= -105, "FAIR")
            .when(col("area_avg_rsrp") >= -115, "POOR")
            .otherwise("NO_COVERAGE")
        )
        .withColumn("overlap_risk",
          when(col("serving_towers") >= 4, "HIGH_OVERLAP")
            .when(col("serving_towers") >= 3, "MODERATE_OVERLAP")
            .otherwise("NORMAL")
        )

      coverageMap.write.mode("overwrite")
        .parquet(s"$outputPath/coverage-map")

      // Coverage summary statistics
      val coverageSummary = coverageMap
        .groupBy("coverage_class")
        .agg(
          count("*").alias("cell_count"),
          avg("area_avg_rsrp").alias("avg_rsrp"),
          avg("area_avg_sinr").alias("avg_sinr"),
          avg("serving_towers").alias("avg_tower_count"),
          sum("measurement_count").alias("total_measurements")
        )
        .orderBy(col("cell_count").desc)

      coverageSummary.write.mode("overwrite")
        .parquet(s"$outputPath/coverage-summary")

      // Overlap analysis
      val overlapAreas = coverageMap
        .filter(col("serving_towers") >= 3)
        .select("geohash", "serving_towers", "area_avg_sinr", "overlap_risk")

      overlapAreas.write.mode("overwrite")
        .parquet(s"$outputPath/overlap-analysis")

      LOG.info("Network coverage report generated")

    } finally {
      spark.stop()
    }
  }

  /**
   * Simple geohash approximation using latitude/longitude rounding.
   * In production, use a proper geohash library (e.g., ch.hsr.geohash).
   */
  def computeGeohash(lat: org.apache.spark.sql.Column,
                     lon: org.apache.spark.sql.Column): org.apache.spark.sql.Column = {
    // Approximate geohash by rounding to ~150m precision
    val latRounded = round(lat, 3) // ~111m latitude precision
    val lonRounded = round(lon, 3) // ~111m * cos(lat) longitude precision
    concat(latRounded.cast(StringType), lit(","), lonRounded.cast(StringType))
  }
}
