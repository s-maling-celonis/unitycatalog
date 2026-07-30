package io.unitycatalog.spark

import org.apache.spark.sql.SparkSessionExtensions

/**
 * Spark 4.0/4.1: route view DDL to UC REST before catalog view-ability checks fail.
 *
 * Primary rewrite happens at parse time via [[ResolveUcViewDdlInParser]] because injected
 * resolution rules run after Spark's built-in `ResolveSessionCatalog`. The analyzer rule below
 * remains as a fallback for programmatic `CreateView` / v1 `CreateViewCommand` paths.
 */
trait UCSparkSessionExtensionsViewSupport {
  def injectViewRules(extensions: SparkSessionExtensions): Unit = {
    extensions.injectResolutionRule { session => ResolveUcViewDdl(session) }
  }
}
