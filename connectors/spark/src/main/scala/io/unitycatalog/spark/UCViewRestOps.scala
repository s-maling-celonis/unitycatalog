package io.unitycatalog.spark

import java.util

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import io.unitycatalog.client.ApiException
import io.unitycatalog.client.api.TablesApi
import io.unitycatalog.client.model.{
  ColumnInfo,
  ColumnTypeName,
  CreateTable,
  Dependency => UCDependency,
  DependencyList => UCDependencyList,
  TableDependency => UCTableDependency,
  TableType
}
import org.apache.spark.sql.catalyst.analysis.ViewAlreadyExistsException
import org.apache.spark.sql.catalyst.catalog.CatalogTable
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.types.{DataType, StructField}

/** Shared REST helpers for plain-view DDL used by Spark 4.2 ViewCatalog and 4.1 extensions. */
private[spark] object UCViewRestOps {

  def emptyDependencyList: UCDependencyList =
    new UCDependencyList().dependencies(new util.ArrayList[UCDependency]())

  def buildColumnInfosFromStructFields(
      fields: Seq[StructField],
      convertDataTypeToTypeName: DataType => ColumnTypeName,
      userSpecifiedColumns: Seq[(String, Option[String])] = Seq.empty): Seq[ColumnInfo] = {
    fields.zipWithIndex.map { case (field, i) =>
      val column = new ColumnInfo()
      val userColumn = userSpecifiedColumns.lift(i)
      val name = userColumn.map(_._1).getOrElse(field.name)
      val comment = userColumn.flatMap(_._2).orElse(field.getComment())
      val persistedField =
        comment.map(c => field.copy(name = name).withComment(c)).getOrElse(field.copy(name = name))
      column.setName(name)
      column.setNullable(field.nullable)
      column.setTypeText(field.dataType.catalogString)
      column.setTypeName(convertDataTypeToTypeName(field.dataType))
      column.setTypeJson(UCColumnConversions.toStructFieldJson(persistedField))
      column.setPosition(i)
      comment.foreach(column.setComment(_))
      column
    }
  }

  def createPlainView(
      tablesApi: TablesApi,
      catalogName: String,
      ident: Identifier,
      queryText: String,
      columns: Seq[ColumnInfo],
      properties: util.Map[String, String],
      viewDependencies: UCDependencyList,
      comment: Option[String],
      sqlConfigs: util.Map[String, String],
      tableType: TableType = TableType.VIEW): Unit = {
    UCSingleCatalog.checkUnsupportedNestedNamespace(ident.namespace())

    val ct = new CreateTable()
      .name(ident.name())
      .schemaName(ident.namespace().head)
      .catalogName(catalogName)
      .tableType(tableType)
      .viewDefinition(queryText)
      .viewDependencies(viewDependencies)
      .columns(columns.asJava)

    comment.foreach(ct.setComment)

    val propertiesToServer = new util.HashMap[String, String]()
    properties.asScala.foreach { case (k, v) =>
      if (UCTableProperties.shouldPersistProperty(k)) {
        propertiesToServer.put(k, v)
      }
    }
    sqlConfigs.asScala.foreach { case (k, v) =>
      propertiesToServer.put(CatalogTable.VIEW_SQL_CONFIG_PREFIX + k, v)
    }
    ct.setProperties(propertiesToServer)

    try {
      tablesApi.createTable(ct)
    } catch {
      case e: ApiException if e.getCode == 409 =>
        throw new ViewAlreadyExistsException(ident)
    }
  }

  def viewExists(tablesApi: TablesApi, catalogName: String, ident: Identifier): Boolean = {
    try {
      val t = tablesApi.getTable(
        UCSingleCatalog.fullTableNameForApi(catalogName, ident),
        /* readStreamingTableAsManaged = */ true,
        /* readMaterializedViewAsManaged = */ true)
      t.getTableType == TableType.VIEW
    } catch {
      case e: ApiException if e.getCode == 404 => false
    }
  }

  def dropView(
      tablesApi: TablesApi,
      catalogName: String,
      ident: Identifier,
      droppableTypes: Set[TableType] =
        Set(TableType.VIEW, TableType.METRIC_VIEW)): Boolean = {
    val fullName = UCSingleCatalog.fullTableNameForApi(catalogName, ident)
    val table = try {
      tablesApi.getTable(fullName, true, true)
    } catch {
      case e: ApiException if e.getCode == 404 => return false
    }
    if (!droppableTypes.contains(table.getTableType)) {
      return false
    }
    tablesApi.deleteTable(fullName)
    true
  }

  def listViewNames(tablesApi: TablesApi, catalogName: String, schema: String): Seq[String] = {
    val names = ArrayBuffer.empty[String]
    var pageToken: String = null
    do {
      val response = tablesApi.listTables(catalogName, schema, /* limit */ 0, pageToken)
      response.getTables.asScala.foreach { t =>
        if (t.getTableType == TableType.VIEW) {
          names += t.getName
        }
      }
      pageToken = response.getNextPageToken
    } while (pageToken != null && pageToken.nonEmpty)
    names.toSeq
  }

  def toUcDependencyList(tableFullNames: Seq[String]): UCDependencyList = {
    val ucDeps = new util.ArrayList[UCDependency]()
    tableFullNames.foreach { fullName =>
      ucDeps.add(new UCDependency()
        .table(new UCTableDependency().tableFullName(fullName)))
    }
    new UCDependencyList().dependencies(ucDeps)
  }
}
