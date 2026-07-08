package io.unitycatalog.server.service;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.utils.ServerProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Parses and validates OAuth client credentials on token exchange requests. */
public class TokenExchangeClientAuthenticator {

  private final ServerProperties serverProperties;

  public TokenExchangeClientAuthenticator(ServerProperties serverProperties) {
    this.serverProperties = serverProperties;
  }

  /**
   * Returns the authenticated OAuth client id when valid credentials are presented.
   *
   * <p>Credentials may be sent as {@code Authorization: Basic} or as {@code client_id} and {@code
   * client_secret} form fields. When the client id matches {@code server.client-id}, the secret
   * must match {@code server.client-secret}. For other client ids, a non-blank secret must be
   * presented.
   */
  public boolean hasCredentials(AggregatedHttpRequest request) {
    return parseCredentials(request).isPresent();
  }

  public Optional<String> authenticate(AggregatedHttpRequest request) {
    Optional<TokenExchangeClientCredentials> credentials = parseCredentials(request);
    if (credentials.isEmpty()) {
      return Optional.empty();
    }

    TokenExchangeClientCredentials creds = credentials.get();
    if (creds.clientId() == null || creds.clientId().isBlank()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT, "client_id must not be blank");
    }

    String configuredClientId = serverProperties.get(ServerProperties.Property.CLIENT_ID);
    String configuredClientSecret = serverProperties.get(ServerProperties.Property.CLIENT_SECRET);

    if (configuredClientId != null && configuredClientId.equals(creds.clientId())) {
      if (configuredClientSecret == null
          || configuredClientSecret.isBlank()
          || !configuredClientSecret.equals(creds.clientSecret())) {
        throw new OAuthInvalidRequestException(
            ErrorCode.UNAUTHENTICATED, "Invalid client credentials");
      }
      return Optional.of(creds.clientId());
    }

    if (creds.clientSecret() == null || creds.clientSecret().isBlank()) {
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED, "Invalid client credentials");
    }
    return Optional.of(creds.clientId());
  }

  private static Optional<TokenExchangeClientCredentials> parseCredentials(
      AggregatedHttpRequest request) {
    Optional<TokenExchangeClientCredentials> fromBasic = parseBasicAuth(request);
    if (fromBasic.isPresent()) {
      return fromBasic;
    }
    return parseFormCredentials(request);
  }

  private static Optional<TokenExchangeClientCredentials> parseBasicAuth(
      AggregatedHttpRequest request) {
    String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION);
    if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      return Optional.empty();
    }
    String encoded = authorization.substring(6).trim();
    if (encoded.isEmpty()) {
      return Optional.empty();
    }
    String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    int separator = decoded.indexOf(':');
    if (separator < 0) {
      return Optional.of(new TokenExchangeClientCredentials(decoded, ""));
    }
    return Optional.of(
        new TokenExchangeClientCredentials(
            decoded.substring(0, separator), decoded.substring(separator + 1)));
  }

  private static Optional<TokenExchangeClientCredentials> parseFormCredentials(
      AggregatedHttpRequest request) {
    if (request.contentType() == null || !request.contentType().belongsTo(MediaType.FORM_DATA)) {
      return Optional.empty();
    }
    Map<String, String> form =
        QueryParams.fromQueryString(request.content(StandardCharsets.UTF_8)).stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b));
    String clientId = form.get("client_id");
    if (clientId == null || clientId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new TokenExchangeClientCredentials(clientId, form.get("client_secret")));
  }
}
