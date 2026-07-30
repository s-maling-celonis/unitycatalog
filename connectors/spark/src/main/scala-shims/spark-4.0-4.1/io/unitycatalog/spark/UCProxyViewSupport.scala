package io.unitycatalog.spark

import scala.collection.JavaConverters._

import io.unitycatalog.client.model.{TableInfo => UCTableInfo, TableType}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{NoSuchTableException, SchemaCompensation}
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable, CatalogTableType}
import org.apache.spark.sql.connector.catalog.{Identifier, Table}
import org.apache.spark.sql.types.StructType

/**
 * Spark 4.0 / 4.1 lack the Spark 4.2 v2 view API (`RelationCatalog` / `ViewCatalog` / `View`), so
 * views cannot be created, listed as views, replaced, renamed, or dropped through the *catalog*
 * surface on these versions. Plain SQL views are still readable: they are surfaced on the table
 * listing and resolved from their SQL text via a V1 VIEW `CatalogTable`. With
 * [[UCSparkSessionExtensions]] registered, CREATE / SHOW / DROP VIEW are instead routed to UC REST
 * at parse time; REPLACE and RENAME remain unsupported. Metric and materialized views stay inert.
 */
trait UCProxyViewSupport { self: UCProxy =>

  protected[spark] def loadViewLikeFromTableSurface(t: UCTableInfo, ident: Identifier): Table =
    if (t.getTableType == TableType.VIEW) buildV1ViewTable(t)
    else throw new NoSuchTableException(ident)

  protected[spark] def hideFromTableListing(tableType: TableType): Boolean =
    UCViewTypes.isViewLikeTableType(tableType) && tableType != TableType.VIEW

  // A UC view has no storage or data source format; Spark resolves it from its SQL text. Returning
  // a VIEW `CatalogTable` routes resolution through Spark's relation resolver, which parses
  // `viewText` against the view's default catalog/namespace.
  protected[spark] def buildV1ViewTable(t: UCTableInfo): Table = {
    val identifier = TableIdentifier(t.getName, Some(t.getSchemaName), Some(t.getCatalogName))
    val fields = Option(t.getColumns).map(_.asScala).getOrElse(Seq.empty)
      .map(self.toStructField).toArray
    val base = Option(t.getProperties).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
    // Spark 4.2 surfaces these through the View API (withQueryColumnNames / withSchemaMode); on v1
    // they are read from properties (viewQueryColumnNames / viewSchemaModeFromProperties), so
    // populate them here for parity. The `view.sqlConfig.*` keys are already carried in `base`.
    //
    // These are only *fallbacks*, overlaid by `base` below: a view created through the UC view DDL
    // extensions persists the real values. Deriving query-output names from the stored columns is
    // wrong for a view with explicit column names (`CREATE VIEW v (renamed) AS SELECT source`),
    // where the stored columns hold the view's names but Spark resolves these keys against the
    // *query's* output names. Likewise, the persisted creation context must win over the view's own
    // catalog/namespace, since the view text was parsed relative to wherever it was created.
    val queryOutFallback =
      if (fields.nonEmpty) {
        Map(CatalogTable.VIEW_QUERY_OUTPUT_NUM_COLUMNS -> fields.length.toString) ++
          fields.zipWithIndex.map { case (f, i) =>
            s"${CatalogTable.VIEW_QUERY_OUTPUT_COLUMN_NAME_PREFIX}$i" -> f.name
          }
      } else {
        Map.empty[String, String]
      }
    val schemaModeFallback = Map(CatalogTable.VIEW_SCHEMA_MODE -> SchemaCompensation.toString)
    val viewNamespaceFallback =
      CatalogTable.catalogAndNamespaceToProps(self.name(), Seq(t.getSchemaName))
    val viewTable = CatalogTable(
      identifier = identifier,
      tableType = CatalogTableType.VIEW,
      storage = CatalogStorageFormat.empty,
      schema = StructType(fields),
      viewText = Option(t.getViewDefinition),
      comment = Option(t.getComment),
      properties = viewNamespaceFallback ++ queryOutFallback ++ schemaModeFallback ++ base,
      createTime = t.getCreatedAt,
      tracksPartitionsInCatalog = false
    )
    self.asV1Table(viewTable)
  }
}
