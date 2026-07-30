package io.unitycatalog.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.UnresolvedIdentifier
import org.apache.spark.sql.catalyst.analysis.UnresolvedNamespace
import org.apache.spark.sql.catalyst.plans.logical.{CreateView, DropView, LogicalPlan, ShowViews}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.connector.catalog.{CatalogPlugin, Identifier}

import ResolveUcViewDdl._
import UcViewDdlCommands._

/**
 * Rewrites persisted view DDL targeting [[UCSingleCatalog]] before analysis reaches
 * [[org.apache.spark.sql.catalyst.analysis.ResolveSessionCatalog]], which rejects v2 catalogs
 * without [[org.apache.spark.sql.connector.catalog.ViewCatalog]].
 *
 * Injected resolution rules from [[UCSparkSessionExtensions]] are appended after
 * `ResolveSessionCatalog` in Spark's analyzer batch, so they cannot intercept `CreateView` in
 * time. This rule therefore runs from [[UCSparkSqlExtensionsParser]] on the freshly parsed plan.
 */
object ResolveUcViewDdlInParser {
  def apply(spark: SparkSession, plan: LogicalPlan): LogicalPlan =
    new ResolveUcViewDdlInParser(spark).apply(plan)
}

private[spark] case class ResolveUcViewDdlInParser(spark: SparkSession) extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!isEnabled(spark)) return plan
    plan.resolveOperators {
      case cv: CreateView if isPersistedCreateView(cv) =>
        ucTarget(cv.child).map { case (catalog, ident) =>
          UcCreateViewViaRestCommand(
            catalog = catalog,
            ident = ident,
            userSpecifiedColumns = cv.userSpecifiedColumns,
            comment = cv.comment,
            properties = cv.properties,
            originalText = cv.originalText,
            plan = cv.query,
            allowExisting = cv.allowExisting,
            replace = cv.replace)
        }.getOrElse(cv)

      case sv: ShowViews =>
        sv.namespace match {
          case UnresolvedNamespace(nameParts, _) =>
            resolveCatalogAndNamespace(nameParts) match {
              case Some((catalog, namespace)) if isUcCatalog(catalog) =>
                UcShowViewsViaRestCommand.fromNamespace(
                  catalog, namespace, sv.pattern, sv.output)
              case _ => sv
            }
          case _ => sv
        }

      case dv: DropView =>
        ucTarget(dv.child, allowTempIdentifiers = true).map { case (catalog, ident) =>
          UcDropViewViaRestCommand(catalog, ident, dv.ifExists)
        }.getOrElse(dv)
    }
  }

  private def ucTarget(
      child: LogicalPlan,
      allowTempIdentifiers: Boolean = false): Option[(CatalogPlugin, Identifier)] =
    child match {
      case UnresolvedIdentifier(nameParts, allowTemp)
          if !allowTemp || (allowTempIdentifiers && !isExistingTempView(nameParts)) =>
        resolveCatalogAndIdent(nameParts)
      case _ => None
    }

  private def isExistingTempView(nameParts: Seq[String]): Boolean = {
    if (nameParts.isEmpty || nameParts.length > 2) {
      false
    } else {
      if (nameParts.length == 2 &&
          spark.sessionState.conf.resolver(
            nameParts.head, spark.sessionState.conf.globalTempDatabase)) {
        return true
      }
      val ident =
        TableIdentifier(nameParts.last, nameParts.dropRight(1).headOption)
      spark.sessionState.catalog.isTempView(ident)
    }
  }

  private def resolveCatalogAndIdent(nameParts: Seq[String]): Option[(CatalogPlugin, Identifier)] = {
    if (nameParts.isEmpty || nameParts.length > 3) return None
    val catalogManager = spark.sessionState.catalogManager
    try {
      val (catalog, ident) = nameParts.length match {
        case 1 =>
          (
            catalogManager.currentCatalog,
            Identifier.of(catalogManager.currentNamespace.toArray, nameParts.head))
        case 2 =>
          (
            catalogManager.currentCatalog,
            Identifier.of(Array(nameParts.head), nameParts(1)))
        case 3 =>
          (
            catalogManager.catalog(nameParts.head),
            Identifier.of(Array(nameParts(1)), nameParts(2)))
      }
      if (isUcCatalog(catalog)) Some((catalog, ident)) else None
    } catch {
      case _: Exception => None
    }
  }

  private def resolveCatalogAndNamespace(
      nameParts: Seq[String]): Option[(CatalogPlugin, Array[String])] = {
    if (nameParts.isEmpty) return None
    val catalogManager = spark.sessionState.catalogManager
    try {
      val (catalog, namespace) = nameParts.length match {
        case 1 =>
          (catalogManager.currentCatalog, Array(nameParts.head))
        case 2 if catalogManager.isCatalogRegistered(nameParts.head) =>
          (catalogManager.catalog(nameParts.head), Array(nameParts(1)))
        case 2 =>
          (catalogManager.currentCatalog, nameParts.toArray)
        case _ => return None
      }
      Some((catalog, namespace))
    } catch {
      case _: Exception => None
    }
  }
}
