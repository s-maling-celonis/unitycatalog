package io.unitycatalog.spark

import org.apache.spark.sql.SparkSessionExtensions

/** Spark 4.0 has no fork view DDL REST routing; no analyzer extension rule. */
trait UCSparkSessionExtensionsViewSupport {
  def injectViewRules(extensions: SparkSessionExtensions): Unit = ()
}
