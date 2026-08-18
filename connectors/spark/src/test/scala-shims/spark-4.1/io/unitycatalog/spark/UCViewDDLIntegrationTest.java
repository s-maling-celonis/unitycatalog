package io.unitycatalog.spark;

import static io.unitycatalog.server.utils.TestUtils.CATALOG_NAME;
import static io.unitycatalog.server.utils.TestUtils.SCHEMA_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.client.model.CreateCatalog;
import io.unitycatalog.spark.utils.OptionsUtil;
import java.util.List;
import lombok.SneakyThrows;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

/**
 * Spark 4.1 lacks native {@code ViewCatalog}; view DDL is routed to UC REST via
 * {@link UCSparkSessionExtensions}. Exercises the full CREATE / SHOW / DROP round-trip through SQL.
 */
public class UCViewDDLIntegrationTest extends AbstractViewReadIntegrationTest {

  @Override
  protected SparkSession createSparkSessionWithCatalogs(String... catalogs) {
    SparkSession.Builder builder =
        SparkSession.builder()
            .appName("test")
            .master("local[*]")
            .config("spark.sql.shuffle.partitions", "4")
            .config(
                "spark.sql.extensions",
                "io.delta.sql.DeltaSparkSessionExtension,"
                    + "io.unitycatalog.spark.UCSparkSessionExtensions");
    for (String catalog : catalogs) {
      String catalogConf = "spark.sql.catalog." + catalog;
      builder =
          builder
              .config(catalogConf, UCSingleCatalog.class.getName())
              .config(catalogConf + "." + OptionsUtil.URI, serverConfig.getServerUrl())
              .config(catalogConf + "." + OptionsUtil.TOKEN, serverConfig.getAuthToken())
              .config(catalogConf + "." + OptionsUtil.WAREHOUSE, catalog)
              .config(catalogConf + "." + OptionsUtil.RENEW_CREDENTIAL_ENABLED, true)
              .config(catalogConf + "." + OptionsUtil.CRED_SCOPED_FS_ENABLED, true);
      if (!List.of(SPARK_CATALOG, CATALOG_NAME).contains(catalog)) {
        createExtraCatalog(catalog);
      }
    }
    if (!List.of(catalogs).contains(SPARK_CATALOG)) {
      builder.config(
          "spark.sql.catalog." + SPARK_CATALOG, "org.apache.spark.sql.delta.catalog.DeltaCatalog");
    }
    builder.config("spark.hadoop.fs.s3.impl", S3CredentialTestFileSystem.class.getName());
    builder.config("spark.hadoop.fs.gs.impl", GCSCredentialTestFileSystem.class.getName());
    builder.config("spark.hadoop.fs.abfs.impl", AzureCredentialTestFileSystem.class.getName());
    return builder.getOrCreate();
  }

  @Override
  protected void createView() {
    sql(
        "CREATE VIEW %s (%s) AS %s",
        VIEW_FULL_NAME, String.join(", ", DECLARED_COLUMNS), VIEW_QUERY);
  }

  @Override
  protected void dropView() {
    sql("DROP VIEW IF EXISTS %s", VIEW_FULL_NAME);
  }

  @Test
  public void testCreateViewSqlBypassesMissingCatalogViewsAbility() {
    session = createSparkSessionWithCatalogs(CATALOG_NAME);
    // Would fail with MISSING_CATALOG_ABILITY.VIEWS without parser-time ResolveUcViewDdlInParser.
    sql("CREATE VIEW %s AS SELECT 1 AS c", VIEW_FULL_NAME);
    assertThat(sql("SHOW VIEWS IN %s.%s", CATALOG_NAME, SCHEMA_NAME))
        .anyMatch(row -> VIEW_NAME.equals(row.getString(1)));
  }

  @Test
  public void testShowViewsListsViewAndDropRemovesIt() {
    createSessionAndView();
    assertThat(sql("SHOW VIEWS IN %s.%s", CATALOG_NAME, SCHEMA_NAME))
        .anyMatch(row -> VIEW_NAME.equals(row.getString(1)));

    sql("DROP VIEW %s", VIEW_FULL_NAME);
    assertThat(sql("SHOW VIEWS IN %s.%s", CATALOG_NAME, SCHEMA_NAME))
        .noneMatch(row -> VIEW_NAME.equals(row.getString(1)));
  }

  @Test
  public void testCreateViewIfNotExistsPreservesExistingView() {
    session = createSparkSessionWithCatalogs(CATALOG_NAME);
    sql("CREATE VIEW %s AS SELECT 1 AS c", VIEW_FULL_NAME);
    sql("CREATE VIEW IF NOT EXISTS %s AS SELECT 2 AS c", VIEW_FULL_NAME);
    assertThat(sql("SELECT * FROM %s", VIEW_FULL_NAME))
        .extracting(row -> row.getInt(0))
        .containsExactly(1);
  }

  @Test
  public void testDropViewIfExistsAcceptsMissingView() {
    session = createSparkSessionWithCatalogs(CATALOG_NAME);
    sql("DROP VIEW IF EXISTS %s", VIEW_FULL_NAME);
  }

  @Test
  public void testExplicitViewColumnNameIsPersisted() {
    session = createSparkSessionWithCatalogs(CATALOG_NAME);
    sql(
        "CREATE VIEW %s (renamed COMMENT 'view comment') AS SELECT 1 AS source_name",
        VIEW_FULL_NAME);
    assertThat(sql("SELECT renamed FROM %s", VIEW_FULL_NAME))
        .extracting(row -> row.getInt(0))
        .containsExactly(1);
  }

  @Test
  public void testCreateOrReplaceViewRejectsWithoutDroppingExistingView() {
    session = createSparkSessionWithCatalogs(CATALOG_NAME);
    sql("CREATE VIEW %s AS SELECT 1 AS c", VIEW_FULL_NAME);
    assertThatThrownBy(
            () -> sql("CREATE OR REPLACE VIEW %s AS SELECT 2 AS c", VIEW_FULL_NAME))
        .hasMessageContaining("no atomic view-replacement API");
    assertThat(sql("SELECT * FROM %s", VIEW_FULL_NAME))
        .extracting(row -> row.getInt(0))
        .containsExactly(1);
  }

  @SneakyThrows
  private void createExtraCatalog(String catalogName) {
    catalogOperations.createCatalog(
        new CreateCatalog().name(catalogName).comment("Created by UCViewDDLIntegrationTest"));
    createdCatalogs.add(catalogName);
  }
}
