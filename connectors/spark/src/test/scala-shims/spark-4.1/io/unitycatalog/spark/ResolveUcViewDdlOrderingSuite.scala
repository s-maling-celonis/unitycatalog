package io.unitycatalog.spark

import io.unitycatalog.spark.UcViewDdlCommands.{
  UcCreateViewViaRestCommand,
  UcDropViewViaRestCommand
}
import io.unitycatalog.spark.utils.OptionsUtil
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.{CreateView, DropView}
import org.junit.jupiter.api.Assertions.{
  assertEquals,
  assertNotEquals,
  assertNotNull,
  assertThrows,
  assertTrue
}
import org.junit.jupiter.api.Test

/**
 * Proves view DDL is rewritten before Spark's `ResolveSessionCatalog` catalog-ability check.
 *
 * Injected analyzer rules from [[UCSparkSessionExtensions]] are ordered after
 * `ResolveSessionCatalog`, so SQL view DDL must be rerouted at parse time via
 * [[ResolveUcViewDdlInParser]].
 */
class ResolveUcViewDdlOrderingSuite {

  private val catalogName = "uc"
  private val schemaName = "default"
  private val viewSql =
    s"CREATE VIEW `$catalogName`.`$schemaName`.`v` AS SELECT 1 AS c"

  @Test
  def parserRewriteReplacesCreateViewBeforeAnalysisForUcCatalogTargets(): Unit = {
    withSparkSession(registerExtensions = true) { spark =>
      val plan = spark.sessionState.sqlParser.parsePlan(viewSql)
      assertTrue(plan.isInstanceOf[UcCreateViewViaRestCommand])
    }
  }

  @Test
  def parsedPlanStaysCreateViewWithoutUcExtensions(): Unit = {
    withSparkSession(registerExtensions = false) { spark =>
      val plan = spark.sessionState.sqlParser.parsePlan(viewSql)
      assertTrue(plan.isInstanceOf[CreateView])
    }
  }

  @Test
  def viewDdlRestRoutingCanBeDisabled(): Unit = {
    withSparkSession(registerExtensions = true) { spark =>
      spark.conf.set(ResolveUcViewDdl.VIEW_DDL_VIA_REST_ENABLED, "false")
      val plan = spark.sessionState.sqlParser.parsePlan(viewSql)
      assertTrue(plan.isInstanceOf[CreateView])
    }
  }

  @Test
  def analysisFailsWithoutUcExtensions(): Unit = {
    withSparkSession(registerExtensions = false) { spark =>
      assertThrows(classOf[AnalysisException], () => spark.sql(viewSql))
    }
  }

  @Test
  def qualifiedShowViewsKeepsItsTargetUcCatalog(): Unit = {
    withSparkSession(registerExtensions = true, makeUcCurrent = false) { spark =>
      val plan = spark.sessionState.sqlParser
        .parsePlan(s"SHOW VIEWS IN `$catalogName`.`$schemaName`")
      val command = plan.asInstanceOf[
        io.unitycatalog.spark.UcViewDdlCommands.UcShowViewsViaRestCommand]
      assertEquals(catalogName, command.catalog.name())
      assertNotEquals(catalogName, spark.sessionState.catalogManager.currentCatalog.name())
    }
  }

  @Test
  def qualifiedDropViewIsRoutedButExistingTempViewIsNot(): Unit = {
    withSparkSession(registerExtensions = true, makeUcCurrent = false) { spark =>
      val ucDrop = spark.sessionState.sqlParser
        .parsePlan(s"DROP VIEW `$catalogName`.`$schemaName`.`v`")
      assertTrue(ucDrop.isInstanceOf[UcDropViewViaRestCommand])

      spark.sql("CREATE TEMP VIEW temp_v AS SELECT 1")
      val tempDrop = spark.sessionState.sqlParser.parsePlan("DROP VIEW temp_v")
      assertTrue(tempDrop.isInstanceOf[DropView])
    }
  }

  @Test
  def viewCreationCapturesSemanticSqlConfigs(): Unit = {
    withSparkSession(registerExtensions = true) { spark =>
      spark.conf.set("spark.sql.ansi.enabled", "false")
      val configs = UcViewDdlPlanUtils.captureSqlConfigs(spark)
      assertEquals("false", configs.get("spark.sql.ansi.enabled"))
      assertNotNull(configs.get("spark.sql.session.timeZone"))
    }
  }

  private def withSparkSession(
      registerExtensions: Boolean,
      makeUcCurrent: Boolean = true)(test: SparkSession => Unit): Unit = {
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    val builder =
      SparkSession.builder()
        .appName("ResolveUcViewDdlOrderingSuite")
        .master("local[1]")
        .config(s"spark.sql.catalog.$catalogName", classOf[UCSingleCatalog].getName)
        .config(s"spark.sql.catalog.$catalogName.${OptionsUtil.URI}", "http://localhost:8080")
        .config(s"spark.sql.catalog.$catalogName.${OptionsUtil.TOKEN}", "token")
        .config(s"spark.sql.catalog.$catalogName.${OptionsUtil.WAREHOUSE}", catalogName)
    if (registerExtensions) {
      builder.config(
        "spark.sql.extensions",
        "io.unitycatalog.spark.UCSparkSessionExtensions")
    }
    val spark = builder.getOrCreate()
    try {
      if (makeUcCurrent) {
        spark.sessionState.catalogManager.setCurrentCatalog(catalogName)
      }
      test(spark)
    } finally {
      spark.stop()
      SparkSession.clearActiveSession()
      SparkSession.clearDefaultSession()
    }
  }
}
