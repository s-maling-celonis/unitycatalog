package io.unitycatalog.docker.tests.support;

import io.unitycatalog.client.ApiException;
import io.unitycatalog.client.api.CatalogsApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class DockerTestConfig {

  /** Celospark maps UC API to host port 8181 (container :8080). */
  public static final String SERVER_URL =
      System.getenv().getOrDefault("UC_SERVER_URL", "http://localhost:8181");
  /** UC REST URL as seen from the spark-server container on the celospark Docker network. */
  public static final String SPARK_UC_SERVER_URI =
      System.getenv().getOrDefault("SPARK_UC_SERVER_URI", "http://unitycatalog:8080");
  public static final Path REPO_ROOT = resolveRepoRoot();
  public static final Path CELOSPARK_DOCKER_DIR = resolveCelosparkDockerDir();
  public static final String BUCKET =
      resolveS3BucketEnv("BUCKET", "CELOSPARK_BUCKET_NAME", "celospark-bucket");
  public static final String DATA_BUCKET =
      resolveS3BucketEnv("DATA_BUCKET", "DP_BUCKET_NAME", "data-pipeline-bucket");
  public static final String STORAGE_ROLE_ARN =
      System.getenv()
          .getOrDefault(
              "STORAGE_ROLE_ARN", "arn:aws:iam::123456789012:role/uc-tenant-storage");
  public static final Path ADMIN_TOKEN_FILE = REPO_ROOT.resolve("etc/conf/token.txt");
  public static final String SPARK_JDBC_HOST =
      System.getenv().getOrDefault("SPARK_JDBC_HOST", "localhost");
  public static final int SPARK_JDBC_PORT =
      Integer.parseInt(System.getenv().getOrDefault("SPARK_JDBC_PORT", "10000"));

  private DockerTestConfig() {}

  public static String adminToken() throws IOException {
    String fromEnv = System.getenv("UC_ADMIN_TOKEN");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    if (!Files.isRegularFile(ADMIN_TOKEN_FILE)) {
      throw new IOException(
          "Admin token missing: set UC_ADMIN_TOKEN (e.g. from celospark OAuth) or create "
              + ADMIN_TOKEN_FILE);
    }
    return Files.readString(ADMIN_TOKEN_FILE).trim();
  }

  public static boolean keepTenants() {
    return "true".equalsIgnoreCase(System.getenv("DOCKER_TESTS_KEEP"));
  }

  public static boolean isServerReachable(String adminToken) {
    try {
      new CatalogsApi(UcClientFactory.catalogClient(SERVER_URL, adminToken)).listCatalogs(null, 1);
      return true;
    } catch (ApiException e) {
      return false;
    }
  }

  public static String tenantPath(String bucket, String tenantId) {
    return bucket + "/tenant/" + tenantId;
  }

  private static Path resolveRepoRoot() {
    String fromEnv = System.getenv("UC_REPO_ROOT");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return Path.of(fromEnv).toAbsolutePath().normalize();
    }
    return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
  }

  private static Path resolveCelosparkDockerDir() {
    String fromEnv = System.getenv("CELOSPARK_DOCKER_DIR");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return Path.of(fromEnv).toAbsolutePath().normalize();
    }
    Path sibling = REPO_ROOT.getParent().resolve("celospark/docker");
    if (Files.isDirectory(sibling)) {
      return sibling.toAbsolutePath().normalize();
    }
    return sibling;
  }

  private static String resolveS3BucketEnv(String s3EnvKey, String dotenvKey, String defaultName) {
    String direct = System.getenv(s3EnvKey);
    if (direct != null && !direct.isBlank()) {
      return direct.trim();
    }
    String fromCompose = System.getenv(dotenvKey);
    if (fromCompose != null && !fromCompose.isBlank()) {
      return toS3Uri(fromCompose.trim());
    }
    try {
      Optional<String> fromFile =
          EnvFileSupport.readVariable(CELOSPARK_DOCKER_DIR.resolve(".env"), dotenvKey);
      if (fromFile.isPresent()) {
        return toS3Uri(fromFile.get());
      }
    } catch (IOException ignored) {
      // fall through to default
    }
    return toS3Uri(defaultName);
  }

  private static String toS3Uri(String bucket) {
    return bucket.startsWith("s3://") ? bucket : "s3://" + bucket;
  }
}
