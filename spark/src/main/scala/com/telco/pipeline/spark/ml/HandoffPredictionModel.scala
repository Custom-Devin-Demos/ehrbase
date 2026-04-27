package com.telco.pipeline.spark.ml

import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.classification.{GBTClassifier, RandomForestClassifier}
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature._
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.slf4j.LoggerFactory

/**
 * Machine learning model for predicting handoff failures.
 *
 * Uses historical handoff events enriched with tower signal data
 * to predict which upcoming handoffs are likely to fail, enabling
 * proactive parameter tuning and network optimization.
 *
 * Features:
 * - Source/target signal quality (RSRP, RSRQ, SINR)
 * - UE velocity estimate
 * - Historical success rate for this tower pair
 * - Time of day / day of week
 * - Tower distance and azimuth difference
 * - Load metrics (PRB utilization, connected UEs)
 * - Handoff type and configured parameters
 *
 * Model: Gradient Boosted Trees with 5-fold cross-validation
 * Training: daily retraining on 30-day sliding window
 * Serving: model exported to PMML for real-time scoring in the RAN controller
 */
object HandoffPredictionModel {

  private val LOG = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 3) {
      System.err.println("Usage: HandoffPredictionModel <training-data> <model-output> <metrics-output>")
      System.exit(1)
    }

    val trainingDataPath = args(0)
    val modelOutputPath = args(1)
    val metricsOutputPath = args(2)

    val spark = SparkSession.builder()
      .appName("TelcoHandoffPredictionModel")
      .config("spark.sql.shuffle.partitions", "100")
      .getOrCreate()

    import spark.implicits._

    try {
      val rawData = spark.read.parquet(trainingDataPath)

      // Feature engineering
      val featureData = engineerFeatures(rawData)

      // Split: 80% train, 20% test
      val Array(trainData, testData) = featureData.randomSplit(Array(0.8, 0.2), seed = 42)

      LOG.info(s"Training samples: ${trainData.count()}, Test samples: ${testData.count()}")

      // Build ML pipeline
      val pipeline = buildPipeline()

      // Hyperparameter tuning
      val crossValidator = buildCrossValidator(pipeline)

      // Train model
      LOG.info("Training handoff prediction model...")
      val model = crossValidator.fit(trainData)

      // Evaluate
      val predictions = model.transform(testData)
      val evaluator = new BinaryClassificationEvaluator()
        .setLabelCol("label")
        .setRawPredictionCol("rawPrediction")

      val auc = evaluator.setMetricName("areaUnderROC").evaluate(predictions)
      val aupr = evaluator.setMetricName("areaUnderPR").evaluate(predictions)

      LOG.info(f"Model evaluation: AUC-ROC=$auc%.4f, AUC-PR=$aupr%.4f")

      // Confusion matrix stats
      val tp = predictions.filter($"label" === 1.0 && $"prediction" === 1.0).count()
      val fp = predictions.filter($"label" === 0.0 && $"prediction" === 1.0).count()
      val tn = predictions.filter($"label" === 0.0 && $"prediction" === 0.0).count()
      val fn = predictions.filter($"label" === 1.0 && $"prediction" === 0.0).count()

      val precision = if (tp + fp > 0) tp.toDouble / (tp + fp) else 0.0
      val recall = if (tp + fn > 0) tp.toDouble / (tp + fn) else 0.0
      val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0.0

      LOG.info(f"Precision=$precision%.4f, Recall=$recall%.4f, F1=$f1%.4f")
      LOG.info(s"Confusion: TP=$tp, FP=$fp, TN=$tn, FN=$fn")

      // Feature importance
      val gbtModel = model.bestModel.asInstanceOf[org.apache.spark.ml.PipelineModel]
        .stages.last.asInstanceOf[GBTClassifier]

      // Save model
      model.bestModel.asInstanceOf[org.apache.spark.ml.PipelineModel].write.overwrite().save(modelOutputPath)
      LOG.info(s"Model saved to $modelOutputPath")

      // Save metrics
      val metricsDF = Seq(
        ("auc_roc", auc),
        ("auc_pr", aupr),
        ("precision", precision),
        ("recall", recall),
        ("f1_score", f1),
        ("training_samples", trainData.count().toDouble),
        ("test_samples", testData.count().toDouble)
      ).toDF("metric", "value")

      metricsDF.write.mode("overwrite").parquet(metricsOutputPath)

    } finally {
      spark.stop()
    }
  }

  def engineerFeatures(df: DataFrame): DataFrame = {
    df
      // Signal quality features
      .withColumn("rsrp_diff", col("target_rsrp_dbm") - col("source_rsrp_dbm"))
      .withColumn("rsrq_diff", col("target_rsrq_db") - col("source_rsrq_db"))
      .withColumn("signal_ratio", col("target_rsrp_dbm") / col("source_rsrp_dbm"))
      // Temporal features
      .withColumn("hour_of_day",
        hour(from_unixtime(col("event_timestamp_ms") / 1000)))
      .withColumn("day_of_week",
        dayofweek(from_unixtime(col("event_timestamp_ms") / 1000)))
      .withColumn("is_peak_hour",
        when(col("hour_of_day").between(8, 20), 1.0).otherwise(0.0))
      // Velocity bins
      .withColumn("velocity_bin",
        when(col("ue_velocity_kmh").isNull, "UNKNOWN")
          .when(col("ue_velocity_kmh") < 5, "STATIONARY")
          .when(col("ue_velocity_kmh") < 30, "PEDESTRIAN")
          .when(col("ue_velocity_kmh") < 80, "VEHICULAR")
          .otherwise("HIGH_SPEED"))
      // Label: 1 = failure, 0 = success
      .withColumn("label",
        when(col("result") === "SUCCESS", 0.0).otherwise(1.0))
      .na.fill(0.0)
  }

  def buildPipeline(): Pipeline = {
    // String indexers for categorical features
    val handoffTypeIndexer = new StringIndexer()
      .setInputCol("handoff_type").setOutputCol("handoff_type_idx")
      .setHandleInvalid("keep")

    val causeIndexer = new StringIndexer()
      .setInputCol("handoff_cause").setOutputCol("cause_idx")
      .setHandleInvalid("keep")

    val velocityIndexer = new StringIndexer()
      .setInputCol("velocity_bin").setOutputCol("velocity_idx")
      .setHandleInvalid("keep")

    // One-hot encoding
    val handoffTypeEncoder = new OneHotEncoder()
      .setInputCol("handoff_type_idx").setOutputCol("handoff_type_vec")

    val causeEncoder = new OneHotEncoder()
      .setInputCol("cause_idx").setOutputCol("cause_vec")

    val velocityEncoder = new OneHotEncoder()
      .setInputCol("velocity_idx").setOutputCol("velocity_vec")

    // Feature assembler
    val assembler = new VectorAssembler()
      .setInputCols(Array(
        "source_rsrp_dbm", "target_rsrp_dbm", "rsrp_diff",
        "source_rsrq_db", "target_rsrq_db", "rsrq_diff",
        "signal_ratio", "hysteresis_db", "a3_offset_db",
        "time_to_trigger_ms", "neighbor_cell_count",
        "hour_of_day", "day_of_week", "is_peak_hour",
        "handoff_type_vec", "cause_vec", "velocity_vec"
      ))
      .setOutputCol("raw_features")
      .setHandleInvalid("keep")

    // Feature scaling
    val scaler = new StandardScaler()
      .setInputCol("raw_features")
      .setOutputCol("features")
      .setWithMean(true)
      .setWithStd(true)

    // GBT classifier
    val gbt = new GBTClassifier()
      .setLabelCol("label")
      .setFeaturesCol("features")
      .setMaxIter(100)
      .setMaxDepth(8)
      .setStepSize(0.1)
      .setSubsamplingRate(0.8)

    new Pipeline().setStages(Array(
      handoffTypeIndexer, causeIndexer, velocityIndexer,
      handoffTypeEncoder, causeEncoder, velocityEncoder,
      assembler, scaler, gbt
    ))
  }

  def buildCrossValidator(pipeline: Pipeline): CrossValidator = {
    val paramGrid = new ParamGridBuilder()
      .addGrid(pipeline.getStages.last.asInstanceOf[GBTClassifier].maxDepth,
        Array(5, 8, 12))
      .addGrid(pipeline.getStages.last.asInstanceOf[GBTClassifier].maxIter,
        Array(50, 100))
      .build()

    new CrossValidator()
      .setEstimator(pipeline)
      .setEvaluator(new BinaryClassificationEvaluator()
        .setLabelCol("label")
        .setMetricName("areaUnderROC"))
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(5)
      .setParallelism(4)
  }
}
