package io.unitycatalog.spark

import io.unitycatalog.client.model.{
  ColumnTypeName,
  CreateTable,
  ListTablesResponse,
  TableInfo,
  TableType
}
import org.apache.spark.sql.catalyst.analysis.ViewAlreadyExistsException
import org.apache.spark.sql.connector.catalog.Identifier
import org.apache.spark.sql.types.IntegerType
import org.junit.jupiter.api.Assertions.{
  assertEquals,
  assertFalse,
  assertNotNull,
  assertThrows,
  assertTrue
}
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{clearInvocations, never, verify, when}

/** Unit tests for [[UCViewRestOps]] used by Spark 4.0/4.1 view DDL routing. */
class UCViewDdlViaRestSuite {

  private val fixture = new UCProxyTestFixture().build()
  private val mockTablesApi = fixture.mockTablesApi
  private val ident = Identifier.of(UCProxyTestFixture.NAMESPACE, "v1")

  @Test
  def createPlainViewSendsViewPayloadWithNonNullDependencies(): Unit = {
    when(mockTablesApi.createTable(any(classOf[CreateTable])))
      .thenReturn(new TableInfo().name("v1").tableType(TableType.VIEW))

    val columns =
      UCViewRestOps.buildColumnInfosFromStructFields(
        Seq(org.apache.spark.sql.types.StructField("c", IntegerType, nullable = true)),
        _ => ColumnTypeName.INT)

    UCViewRestOps.createPlainView(
      tablesApi = mockTablesApi,
      catalogName = UCProxyTestFixture.CATALOG_NAME,
      ident = ident,
      queryText = "SELECT 1 AS c",
      columns = columns,
      properties = new java.util.HashMap[String, String](),
      viewDependencies = UCViewRestOps.emptyDependencyList,
      comment = None,
      sqlConfigs = new java.util.HashMap[String, String](),
      tableType = TableType.VIEW)

    val captor = org.mockito.ArgumentCaptor.forClass(classOf[CreateTable])
    verify(mockTablesApi).createTable(captor.capture())
    assertEquals(TableType.VIEW, captor.getValue.getTableType)
    assertEquals("SELECT 1 AS c", captor.getValue.getViewDefinition)
    assertNotNull(captor.getValue.getViewDependencies)
    assertTrue(captor.getValue.getViewDependencies.getDependencies.isEmpty)
  }

  @Test
  def createPlainViewMaps409ToViewAlreadyExistsException(): Unit = {
    when(mockTablesApi.createTable(any(classOf[CreateTable])))
      .thenThrow(new io.unitycatalog.client.ApiException(409, "exists"))

    val columns =
      UCViewRestOps.buildColumnInfosFromStructFields(
        Seq(org.apache.spark.sql.types.StructField("c", IntegerType, nullable = true)),
        _ => ColumnTypeName.INT)

    assertThrows(
      classOf[ViewAlreadyExistsException],
      () =>
        UCViewRestOps.createPlainView(
          tablesApi = mockTablesApi,
          catalogName = UCProxyTestFixture.CATALOG_NAME,
          ident = ident,
          queryText = "SELECT 1 AS c",
          columns = columns,
          properties = new java.util.HashMap[String, String](),
          viewDependencies = UCViewRestOps.emptyDependencyList,
          comment = None,
          sqlConfigs = new java.util.HashMap[String, String]()))
  }

  @Test
  def buildColumnInfosAppliesExplicitViewColumnNamesAndComments(): Unit = {
    val columns =
      UCViewRestOps.buildColumnInfosFromStructFields(
        Seq(org.apache.spark.sql.types.StructField("source_name", IntegerType, nullable = true)),
        _ => ColumnTypeName.INT,
        Seq("view_name" -> Some("view comment")))

    assertEquals("view_name", columns.head.getName)
    assertEquals("view comment", columns.head.getComment)
  }

  @Test
  def listViewNamesFiltersToPlainViews(): Unit = {
    val response = new ListTablesResponse()
      .tables(
        java.util.List.of(
          new TableInfo().name("t1").tableType(TableType.EXTERNAL),
          new TableInfo().name("v1").tableType(TableType.VIEW),
          new TableInfo().name("mv1").tableType(TableType.METRIC_VIEW)))
      .nextPageToken(null)
    when(
      mockTablesApi.listTables(
        UCProxyTestFixture.CATALOG_NAME,
        UCProxyTestFixture.SCHEMA_NAME,
        0,
        null)).thenReturn(response)

    assertEquals(
      Seq("v1"),
      UCViewRestOps.listViewNames(
        mockTablesApi,
        UCProxyTestFixture.CATALOG_NAME,
        UCProxyTestFixture.SCHEMA_NAME))
  }

  @Test
  def dropViewDeletesExistingView(): Unit = {
    when(
      mockTablesApi.getTable(
        s"${UCProxyTestFixture.CATALOG_NAME}.${UCProxyTestFixture.SCHEMA_NAME}.v1",
        true,
        true)).thenReturn(new TableInfo().name("v1").tableType(TableType.VIEW))

    assertTrue(UCViewRestOps.dropView(mockTablesApi, UCProxyTestFixture.CATALOG_NAME, ident))
    verify(mockTablesApi).deleteTable(
      s"${UCProxyTestFixture.CATALOG_NAME}.${UCProxyTestFixture.SCHEMA_NAME}.v1")
  }

  @Test
  def dropViewDoesNotDeleteRegularTable(): Unit = {
    clearInvocations(mockTablesApi)
    when(
      mockTablesApi.getTable(
        s"${UCProxyTestFixture.CATALOG_NAME}.${UCProxyTestFixture.SCHEMA_NAME}.v1",
        true,
        true)).thenReturn(new TableInfo().name("v1").tableType(TableType.EXTERNAL))

    assertFalse(UCViewRestOps.dropView(mockTablesApi, UCProxyTestFixture.CATALOG_NAME, ident))
    verify(mockTablesApi, never()).deleteTable(anyString())
  }

  @Test
  def dropViewDoesNotDeleteUnsupportedViewKind(): Unit = {
    clearInvocations(mockTablesApi)
    when(
      mockTablesApi.getTable(
        s"${UCProxyTestFixture.CATALOG_NAME}.${UCProxyTestFixture.SCHEMA_NAME}.v1",
        true,
        true)).thenReturn(new TableInfo().name("v1").tableType(TableType.MATERIALIZED_VIEW))

    assertFalse(UCViewRestOps.dropView(mockTablesApi, UCProxyTestFixture.CATALOG_NAME, ident))
    verify(mockTablesApi, never()).deleteTable(anyString())
  }

  @Test
  def spark40And41DropViewDoesNotDeleteMetricView(): Unit = {
    clearInvocations(mockTablesApi)
    when(
      mockTablesApi.getTable(
        s"${UCProxyTestFixture.CATALOG_NAME}.${UCProxyTestFixture.SCHEMA_NAME}.v1",
        true,
        true)).thenReturn(new TableInfo().name("v1").tableType(TableType.METRIC_VIEW))

    assertFalse(
      UCViewRestOps.dropView(
        mockTablesApi,
        UCProxyTestFixture.CATALOG_NAME,
        ident,
        Set(TableType.VIEW)))
    verify(mockTablesApi, never()).deleteTable(anyString())
  }
}
