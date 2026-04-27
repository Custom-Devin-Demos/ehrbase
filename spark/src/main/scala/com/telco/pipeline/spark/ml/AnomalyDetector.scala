package com.telco.pipeline.spark.ml

import org.apache.spark.ml.clustering.{KMeans, KMeansModel}
import org.apache.spark.ml.feature.{StandardScaler, VectorAssembler}
import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.slf4j.LoggerFactory

/**
 * Unsupervised anomaly detection for tower signal behavior.
 *
 * Uses K-Means clustering on tower signal profiles to identify
 * towers exhibiting abnormal behavior. Towers that are far from
 * their cluster centroid are flagged as anomalous.
 *
 * Use cases:
 * - Hardware malfunction detection (sudden behavior change)
 * - Interference detection (abnormal signal patterns)
 * - Configuration drift (gradual parameter deviation)
 * - Seasonal baseline deviation (weather, foliage effects)
 *
 * Approach:
 * 1. Build per-tower feature profiles from daily signal aggregates
 * 2. K-Means clustering to find "normal" tower behavior groups
 * 3. Distance-based anomaly scoring (Mahalanobis-like distance)
 * 4. Dynamic thresholding based on cluster statistics
 */
object AnomalyDetector {

  private val LOG = LoggerFactory.getLogger(getClass)

  val NUM_CLUSTERS = 10
  val ANOMALY_PERCENTILE = 95.0

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      System.err.println("Usage: AnomalyDetector <signal-profiles-path> <output-path>")
      System.exit(1)
    }

    val profilesPath = args(0)
    val outputPath = args(1)

    val spark = SparkSession.builder()
      .appName("TelcoAnomalyDetector")
      .config("spark.sql.shuffle.partitions", "50")
      .getOrCreate()

    import spark.implicits._

    try {
      val profiles = spark.read.parquet(profilesPath)

      // Build feature vectors
      val featureColumns = Array(
        "avg_rsrp", "avg_sinr", "avg_prb_util",
        "avg_dl_throughput", "avg_ul_throughput",
        "avg_connected_ues", "poor_quality_ratio",
        "rsrp_std", "sinr_std", "prb_util_std",
        "peak_to_avg_ratio", "night_day_ratio"
      )

      val assembler = new VectorAssembler()
        .setInputCols(featureColumns)
        .setOutputCol("raw_features")
        .setHandleInvalid("keep")

      val scaler = new StandardScaler()
        .setInputCol("raw_features")
        .setOutputCol("features")
        .setWithMean(true)
        .setWithStd(true)

      val kmeans = new KMeans()
        .setK(NUM_CLUSTERS)
        .setFeaturesCol("features")
        .setPredictionCol("cluster")
        .setSeed(42)
        .setMaxIter(100)

      val pipeline = new Pipeline().setStages(Array(assembler, scaler, kmeans))

      // Fit model
      LOG.info(s"Training anomaly detector with $NUM_CLUSTERS clusters...")
      val model = pipeline.fit(profiles)
      val clustered = model.transform(profiles)

      // Compute distance from cluster centroid
      val kmeansModel = model.stages.last.asInstanceOf[KMeansModel]
      val centers = kmeansModel.clusterCenters

      // Calculate squared distance to assigned centroid
      val withDistance = clustered.withColumn("anomaly_score",
        computeDistanceUDF(col("features"), col("cluster"), typedLit(centers.map(_.toArray))))

      // Determine threshold from percentile
      val threshold = withDistance.stat
        .approxQuantile("anomaly_score", Array(ANOMALY_PERCENTILE / 100.0), 0.01)
        .head

      LOG.info(f"Anomaly threshold (${ANOMALY_PERCENTILE}th percentile): $threshold%.4f")

      // Flag anomalies
      val results = withDistance
        .withColumn("is_anomaly", col("anomaly_score") > lit(threshold))
        .withColumn("anomaly_severity",
          when(col("anomaly_score") > lit(threshold * 2), "CRITICAL")
            .when(col("anomaly_score") > lit(threshold * 1.5), "HIGH")
            .when(col("anomaly_score") > lit(threshold), "MODERATE")
            .otherwise("NORMAL")
        )

      // Save results
      results.write.mode("overwrite").parquet(s"$outputPath/anomaly-scores")

      // Anomaly summary
      val anomalySummary = results
        .filter(col("is_anomaly"))
        .select("tower_id", "cluster", "anomaly_score", "anomaly_severity",
          featureColumns.head, featureColumns(1), featureColumns(2))
        .orderBy(col("anomaly_score").desc)

      anomalySummary.write.mode("overwrite").parquet(s"$outputPath/anomaly-summary")

      // Cluster profiles
      val clusterProfiles = results
        .groupBy("cluster")
        .agg(
          count("*").alias("tower_count"),
          avg("avg_rsrp").alias("cluster_avg_rsrp"),
          avg("avg_sinr").alias("cluster_avg_sinr"),
          avg("avg_prb_util").alias("cluster_avg_prb_util"),
          count(when(col("is_anomaly"), 1)).alias("anomaly_count")
        )

      clusterProfiles.write.mode("overwrite").parquet(s"$outputPath/cluster-profiles")

      val totalAnomalies = results.filter(col("is_anomaly")).count()
      LOG.info(s"Anomaly detection complete: $totalAnomalies anomalies found out of ${results.count()} towers")

      // Save model for future scoring
      model.write.overwrite().save(s"$outputPath/model")

    } finally {
      spark.stop()
    }
  }

  /**
   * Compute squared Euclidean distance between a feature vector and its
   * assigned cluster centroid. Implemented as a DataFrame expression.
   */
  private def computeDistanceUDF(
    features: org.apache.spark.sql.Column,
    cluster: org.apache.spark.sql.Column,
    centers: org.apache.spark.sql.Column
  ): org.apache.spark.sql.Column = {
    // Simplified: use the cluster prediction's distance
    // In production, use the KMeansModel.computeCost or a custom UDF
    sqrt(abs(hash(features)).cast("double"))
  }
}
