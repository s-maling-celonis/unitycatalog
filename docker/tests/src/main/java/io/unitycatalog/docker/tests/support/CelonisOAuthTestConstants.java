package io.unitycatalog.docker.tests.support;

public final class CelonisOAuthTestConstants {

  public static final String OAUTH_CLIENT_ID = "unity-catalog-local";
  public static final String OAUTH_CLIENT_SECRET = "unity-catalog-local-secret";
  public static final String OAUTH_TEAM_ID = "79257834-828d-48cb-951d-75294d6e1cce";
  public static final String OAUTH_TEAM_DOMAIN = "dev";
  public static final String OAUTH_INTERNAL_JWT_SECRET = "my-super-secret-key";
  public static final String OAUTH_REDIRECT_URI = "http://127.0.0.1:8080/callback";
  public static final String OAUTH_INTERNAL_SERVICE_NAME = "unity-catalog-tests";

  private static final String DEFAULT_OAUTH_CONNECT_URL = "http://127.0.0.1:9010";
  private static final String DEFAULT_OAUTH_HOST = "dev.dev.celonis.cloud";

  private CelonisOAuthTestConstants() {}

  /** TCP target for local Envoy (host → published :9010). Tenant routing uses {@link #oauthHostHeader()}. */
  public static String oauthConnectUrl() {
    String fromEnv = System.getenv("UC_OAUTH_CONNECT_URL");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.replaceAll("/$", "");
    }
    return DEFAULT_OAUTH_CONNECT_URL;
  }

  /** Virtual host for gateway ext_authz ({@code Host} header). */
  public static String oauthHostHeader() {
    String fromEnv = System.getenv("UC_OAUTH_HOST");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    return DEFAULT_OAUTH_HOST;
  }
}
