package io.unitycatalog.spark

import java.util

import org.apache.spark.sql.{AnalysisException, Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{
  AnalysisContext,
  GlobalTempView,
  LocalTempView,
  NoSuchViewException,
  ResolvedIdentifier,
  ResolvedNamespace,
  ResolvedTable,
  ViewAlreadyExistsException
}
import org.apache.spark.sql.catalyst.catalog.CatalogTable
import org.apache.spark.sql.catalyst.plans.logical.{
  CreateView,
  DropView,
  LogicalPlan,
  ShowViews
}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.util.StringUtils
import org.apache.spark.sql.connector.catalog.{CatalogPlugin, Identifier}
import org.apache.spark.sql.execution.command.{
  CreateViewCommand,
  RunnableCommand,
  ShowViewsCommand
}
import io.unitycatalog.client.model.TableType

/**
 * On Spark 4.1, reroutes persisted view DDL for [[UCSingleCatalog]] to UC REST.
 *
 * SQL view DDL is normally rewritten earlier in [[ResolveUcViewDdlInParser]]; this rule covers
 * programmatic `CreateView` plans and v1 `CreateViewCommand` paths that still reach analysis.
 */
case class ResolveUcViewDdl(spark: SparkSession) extends Rule[LogicalPlan] {

  import ResolveUcViewDdl._
  import UcViewDdlCommands._

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!isEnabled(spark)) return plan
    plan.resolveOperators {
      case cv: CreateView if isPersistedCreateView(cv) =>
        resolveCreateView(cv)

      case cvc: CreateViewCommand if isPersistedCreateViewCommand(cvc) =>
        resolveCreateViewCommand(cvc)

      case sv: ShowViews =>
        resolveShowViews(sv)

      case svc: ShowViewsCommand if isCurrentUcCatalog(spark) =>
        UcShowViewsViaRestCommand.fromDatabase(
          spark.sessionState.catalogManager.currentCatalog,
          svc.databaseName,
          svc.tableIdentifierPattern,
          svc.output)

      case dv: DropView =>
        resolveDropView(dv)
    }
  }

  private def resolveCreateView(cv: CreateView): LogicalPlan = {
    cv.left match {
      case ResolvedIdentifier(catalog, ident) if isUcCatalog(catalog) =>
        if (!cv.query.resolved) cv
        else {
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
        }
      case _ => cv
    }
  }

  private def resolveCreateViewCommand(cvc: CreateViewCommand): LogicalPlan = {
    if (!isCurrentUcCatalog(spark)) return cvc
    if (!cvc.isAnalyzed || !cvc.plan.resolved) return cvc
    val catalog = spark.sessionState.catalogManager.currentCatalog
    val ident = tableIdentifierToIdentifier(cvc.name, spark)
    UcCreateViewViaRestCommand.fromCreateViewCommand(cvc, ident, catalog)
  }

  private def resolveShowViews(sv: ShowViews): LogicalPlan = {
    sv.namespace match {
      case ResolvedNamespace(catalog, namespace, _) if isUcCatalog(catalog) =>
        UcShowViewsViaRestCommand.fromNamespace(
          catalog, namespace.toArray, sv.pattern, sv.output)
      case _ => sv
    }
  }

  private def resolveDropView(dv: DropView): LogicalPlan = {
    dv.child match {
      case ResolvedIdentifier(catalog, ident) if isUcCatalog(catalog) =>
        UcDropViewViaRestCommand(catalog, ident, dv.ifExists)
      case _ => dv
    }
  }
}

object ResolveUcViewDdl {

  val VIEW_DDL_VIA_REST_ENABLED = "spark.sql.unitycatalog.viewDdlViaRest.enabled"

  def isEnabled(spark: SparkSession): Boolean =
    spark.conf.get(VIEW_DDL_VIA_REST_ENABLED, "true").toBoolean

  def isUcCatalog(catalog: CatalogPlugin): Boolean =
    catalog.isInstanceOf[UCSingleCatalog]

  def isCurrentUcCatalog(spark: SparkSession): Boolean =
    isUcCatalog(spark.sessionState.catalogManager.currentCatalog)

  def isPersistedCreateView(cv: CreateView): Boolean = true

  def isPersistedCreateViewCommand(cvc: CreateViewCommand): Boolean =
    cvc.viewType != LocalTempView && cvc.viewType != GlobalTempView

  def tableIdentifierToIdentifier(name: TableIdentifier, spark: SparkSession): Identifier = {
    val schema = name.database.getOrElse(
      spark.sessionState.catalogManager.currentNamespace.head)
    Identifier.of(Array(schema), name.table)
  }
}

object UcViewDdlCommands {

  case class UcCreateViewViaRestCommand(
      catalog: CatalogPlugin,
      ident: Identifier,
      userSpecifiedColumns: Seq[(String, Option[String])],
      comment: Option[String],
      properties: Map[String, String],
      originalText: Option[String],
      plan: LogicalPlan,
      allowExisting: Boolean,
      replace: Boolean,
      isAnalyzed: Boolean = false)
      extends RunnableCommand
      with org.apache.spark.sql.catalyst.plans.logical.AnalysisOnlyCommand {

    override protected def withNewChildrenInternal(
        newChildren: IndexedSeq[LogicalPlan]): UcCreateViewViaRestCommand = {
      assert(!isAnalyzed)
      copy(plan = newChildren.head)
    }

    override def childrenToAnalyze: Seq[LogicalPlan] = plan :: Nil

    override def markAsAnalyzed(analysisContext: AnalysisContext): LogicalPlan =
      copy(isAnalyzed = true)

    override def run(sparkSession: SparkSession): Seq[Row] = {
      if (!isAnalyzed) {
        throw new AnalysisException(
          errorClass = "LOGICAL_PLAN_FOR_VIEW_NOT_ANALYZED",
          messageParameters = Map.empty)
      }
      val uc = catalog.asInstanceOf[UCSingleCatalog]
      val tablesApi = uc.ucProxy.tablesApi
      val catalogName = catalog.name()
      val queryText = originalText.getOrElse {
        throw new UnsupportedOperationException(
          "Creating a persisted Unity Catalog view requires the original SQL query text")
      }
      val columns =
        UCViewRestOps.buildColumnInfosFromStructFields(
          plan.schema.fields.toSeq,
          uc.ucProxy.convertDataTypeToTypeName(_),
          userSpecifiedColumns)
      val props = new util.HashMap[String, String]()
      properties.foreach { case (k, v) => props.put(k, v) }
      props.put(UCTableProperties.PROP_TABLE_TYPE, "VIEW")
      UcViewDdlPlanUtils.addViewResolutionProperties(
        props,
        sparkSession,
        plan.output.map(_.name))
      val deps = UcViewDdlPlanUtils.extractTableDependencies(plan, catalogName)

      if (allowExisting && UCViewRestOps.viewExists(tablesApi, catalogName, ident)) {
        return Seq.empty
      }
      if (replace && UCViewRestOps.viewExists(tablesApi, catalogName, ident)) {
        throw new UnsupportedOperationException(
          "CREATE OR REPLACE VIEW is not supported because Unity Catalog has no atomic " +
            "view-replacement API")
      } else if (!replace && !allowExisting &&
        UCViewRestOps.viewExists(tablesApi, catalogName, ident)) {
        throw new ViewAlreadyExistsException(ident)
      }

      UCViewRestOps.createPlainView(
        tablesApi = tablesApi,
        catalogName = catalogName,
        ident = ident,
        queryText = queryText,
        columns = columns,
        properties = props,
        viewDependencies = deps,
        comment = comment,
        sqlConfigs = UcViewDdlPlanUtils.captureSqlConfigs(sparkSession),
        tableType = TableType.VIEW)
      Seq.empty
    }
  }

  object UcCreateViewViaRestCommand {
    def fromCreateViewCommand(
        cvc: CreateViewCommand,
        ident: Identifier,
        catalog: CatalogPlugin): UcCreateViewViaRestCommand =
      UcCreateViewViaRestCommand(
        catalog = catalog,
        ident = ident,
        userSpecifiedColumns = cvc.userSpecifiedColumns,
        comment = cvc.comment,
        properties = cvc.properties,
        originalText = cvc.originalText,
        plan = cvc.plan,
        allowExisting = cvc.allowExisting,
        replace = cvc.replace)
  }

  case class UcDropViewViaRestCommand(
      catalog: CatalogPlugin,
      ident: Identifier,
      ifExists: Boolean) extends org.apache.spark.sql.execution.command.LeafRunnableCommand {

    override def run(sparkSession: SparkSession): Seq[Row] = {
      val uc = catalog.asInstanceOf[UCSingleCatalog]
      val dropped =
        UCViewRestOps.dropView(
          uc.ucProxy.tablesApi,
          catalog.name(),
          ident,
          Set(TableType.VIEW))
      if (!dropped && !ifExists) {
        throw new NoSuchViewException(ident)
      }
      Seq.empty
    }
  }

  case class UcShowViewsViaRestCommand(
      catalog: CatalogPlugin,
      namespace: Array[String],
      pattern: Option[String],
      override val output: Seq[org.apache.spark.sql.catalyst.expressions.Attribute])
      extends org.apache.spark.sql.execution.command.LeafRunnableCommand {

    override def run(sparkSession: SparkSession): Seq[Row] = {
      val uc = catalog.asInstanceOf[UCSingleCatalog]
      UCSingleCatalog.checkUnsupportedNestedNamespace(namespace)
      val schema = namespace.head
      val viewNames = UCViewRestOps.listViewNames(uc.ucProxy.tablesApi, catalog.name(), schema)
      val filtered = pattern match {
        case Some(p) => StringUtils.filterPattern(viewNames, p)
        case None => viewNames
      }
      filtered.map { viewName =>
        Row(namespace.mkString("."), viewName, false)
      }
    }
  }

  object UcShowViewsViaRestCommand {
    def fromNamespace(
        catalog: CatalogPlugin,
        namespace: Array[String],
        pattern: Option[String],
        output: Seq[org.apache.spark.sql.catalyst.expressions.Attribute]): UcShowViewsViaRestCommand =
      UcShowViewsViaRestCommand(catalog, namespace, pattern, output)

    def fromDatabase(
        catalog: CatalogPlugin,
        databaseName: String,
        tableIdentifierPattern: Option[String],
        output: Seq[org.apache.spark.sql.catalyst.expressions.Attribute]): UcShowViewsViaRestCommand =
      UcShowViewsViaRestCommand(catalog, Array(databaseName), tableIdentifierPattern, output)
  }
}

private[spark] object UcViewDdlPlanUtils {

  private val CONFIG_PREFIX_DENY_LIST = Seq(
    "spark.sql.view.maxNestedViewDepth",
    "spark.sql.optimizer.",
    "spark.sql.codegen.",
    "spark.sql.execution.",
    "spark.sql.shuffle.",
    "spark.sql.adaptive.",
    "spark.sql.hive.convertMetastoreParquet",
    "spark.sql.hive.convertMetastoreOrc",
    "spark.sql.hive.convertInsertingPartitionedTable",
    "spark.sql.hive.convertInsertingUnpartitionedTable",
    "spark.sql.hive.convertMetastoreCtas",
    "spark.sql.maven.additionalRemoteRepositories",
    "spark.sql.analyzer.singlePassResolver.enabledTentatively",
    "spark.sql.analyzer.singlePassResolver.dualRunWithLegacy")

  private val ALWAYS_CAPTURED_CONFIGS =
    Seq("spark.sql.session.timeZone", "spark.sql.ansi.enabled")

  def captureSqlConfigs(spark: SparkSession): util.Map[String, String] = {
    val conf = spark.sessionState.conf
    val captured = new util.HashMap[String, String]()
    conf.getAllConfs.foreach { case (key, value) =>
      if (conf.isModifiable(key) && !CONFIG_PREFIX_DENY_LIST.exists(key.startsWith)) {
        captured.put(key, value)
      }
    }
    ALWAYS_CAPTURED_CONFIGS.foreach { key =>
      if (!captured.containsKey(key)) {
        captured.put(key, conf.getConfString(key))
      }
    }
    captured
  }

  def addViewResolutionProperties(
      properties: util.Map[String, String],
      spark: SparkSession,
      queryOutputNames: Seq[String]): Unit = {
    val catalogManager = spark.sessionState.catalogManager
    CatalogTable.catalogAndNamespaceToProps(
      catalogManager.currentCatalog.name(),
      catalogManager.currentNamespace.toSeq).foreach { case (key, value) =>
      properties.put(key, value)
    }
    properties.put(
      CatalogTable.VIEW_QUERY_OUTPUT_NUM_COLUMNS,
      queryOutputNames.length.toString)
    queryOutputNames.zipWithIndex.foreach { case (name, index) =>
      properties.put(s"${CatalogTable.VIEW_QUERY_OUTPUT_COLUMN_NAME_PREFIX}$index", name)
    }
  }

  def extractTableDependencies(
      plan: LogicalPlan,
      defaultCatalog: String): io.unitycatalog.client.model.DependencyList = {
    val tableNames = plan.collect {
      case ResolvedTable(catalog, ident, _, _) =>
        UCSingleCatalog.fullTableNameForApi(catalog.name(), ident)
    }.distinct
    if (tableNames.isEmpty) {
      UCViewRestOps.emptyDependencyList
    } else {
      UCViewRestOps.toUcDependencyList(tableNames)
    }
  }
}
