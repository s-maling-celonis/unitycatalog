package io.unitycatalog.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan

/** Spark 4.0 has no fork view DDL REST routing; pass through unchanged. */
object ResolveUcViewDdlInParser {
  def apply(spark: SparkSession, plan: LogicalPlan): LogicalPlan = plan
}
