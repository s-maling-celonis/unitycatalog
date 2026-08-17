package io.unitycatalog.spark

import org.apache.spark.sql.SparkSessionExtensions

/** Spark 4.2 uses native ViewCatalog for view DDL; no REST extension rule. */
trait UCSparkSessionExtensionsViewSupport {
  def injectViewRules(extensions: SparkSessionExtensions): Unit = ()
}
