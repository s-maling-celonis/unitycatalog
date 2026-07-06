package io.unitycatalog.docker.tests.support;

import java.io.IOException;

/**
 * Celospark {@code docker compose -f compose.uc.yml -f uc/oidc/compose.yaml} owns MinIO, UC, OAuth,
 * and spark-server. Tests only wait for the Thrift listener before JDBC.
 */
public final class DockerCompose {

  private static volatile boolean sparkReady;

  private DockerCompose() {}

  public static void upSparkStack(String catalog, String userToken)
      throws IOException, InterruptedException {
    if (sparkReady) {
      return;
    }
    SparkJdbcClient.waitForPort();
    sparkReady = true;
  }
}
