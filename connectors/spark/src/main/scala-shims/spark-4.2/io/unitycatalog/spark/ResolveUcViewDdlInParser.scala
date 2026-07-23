package io.unitycatalog.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan

/** Spark 4.2 uses native ViewCatalog; no parser-time view DDL rewrite. */
object ResolveUcViewDdlInParser {
  def apply(spark: SparkSession, plan: LogicalPlan): LogicalPlan = plan
}
