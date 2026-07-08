package io.unitycatalog.server.service;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import io.unitycatalog.control.model.TokenType;
import io.unitycatalog.control.model.User;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.security.JwtClaim;
import io.unitycatalog.server.utils.AudienceAllowlist;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves UC principals and validates subject token audiences during token exchange. */
public class TokenExchangePrincipalResolver {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TokenExchangePrincipalResolver.class);

  private final ServerProperties serverProperties;
  private final UserRepository userRepository;
  private final TokenExchangeClientAuthenticator clientAuthenticator;

  public TokenExchangePrincipalResolver(
      ServerProperties serverProperties, UserRepository userRepository) {
    this.serverProperties = serverProperties;
    this.userRepository = userRepository;
    this.clientAuthenticator = new TokenExchangeClientAuthenticator(serverProperties);
  }

  /** Validates audience rules and returns the UC principal email for the issued access token. */
  public String resolvePrincipalEmail(
      TokenType subjectTokenType, DecodedJWT decodedJWT, AggregatedHttpRequest request) {
    Optional<String> authenticatedClientId = clientAuthenticator.authenticate(request);
    if (authenticatedClientId.isPresent()) {
      String clientId = authenticatedClientId.get();
      validateSubjectTokenForClient(decodedJWT, clientId);
      return resolvePrincipalEmailForClient(clientId);
    }

    validateConfiguredAudience(subjectTokenType, decodedJWT);
    return resolvePrincipalEmailFromTokenClaims(decodedJWT);
  }

  private void validateSubjectTokenForClient(DecodedJWT decodedJWT, String clientId) {
    if (subjectTokenReferencesClient(decodedJWT, clientId)) {
      return;
    }
    LOGGER.debug("Token rejected: subject token not issued for client '{}'", clientId);
    throw new OAuthInvalidRequestException(ErrorCode.UNAUTHENTICATED, "Invalid audience");
  }

  private static boolean subjectTokenReferencesClient(DecodedJWT decodedJWT, String clientId) {
    Claim azp = decodedJWT.getClaim("azp");
    if (!azp.isNull() && clientId.equals(azp.asString())) {
      return true;
    }
    List<String> audiences = decodedJWT.getAudience();
    return audiences != null && audiences.contains(clientId);
  }

  private void validateConfiguredAudience(TokenType subjectTokenType, DecodedJWT decodedJWT) {
    List<String> audiences = serverProperties.getAudiences();
    if (audiences.isEmpty()) {
      LOGGER.error("No audiences configured");
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "No audiences configured. Set server.audiences in server.properties");
    }

    if (serverProperties.isAudienceValidationDisabled()) {
      return;
    }

    if (AudienceAllowlist.isAllowed(decodedJWT.getAudience(), audiences)) {
      return;
    }

    if (subjectTokenType == TokenType.ID_TOKEN) {
      String configuredClientId = serverProperties.get(ServerProperties.Property.CLIENT_ID);
      if (configuredClientId != null
          && subjectTokenReferencesClient(decodedJWT, configuredClientId)) {
        return;
      }
    }

    LOGGER.debug("Token rejected: audience not in allowlist");
    throw new OAuthInvalidRequestException(ErrorCode.UNAUTHENTICATED, "Invalid audience");
  }

  private String resolvePrincipalEmailForClient(String clientId) {
    LOGGER.debug("Resolving principal for OAuth client id {}", clientId);
    try {
      User user = userRepository.getUserByExternalId(clientId);
      if (user != null && user.getState() == User.StateEnum.ENABLED) {
        LOGGER.debug("Principal for client {} resolved to {}", clientId, user.getEmail());
        return user.getEmail();
      }
    } catch (Exception e) {
      // IGNORE
    }
    throw new OAuthInvalidRequestException(
        ErrorCode.INVALID_ARGUMENT, "User not allowed: " + clientId);
  }

  private String resolvePrincipalEmailFromTokenClaims(DecodedJWT decodedJWT) {
    String subject =
        decodedJWT
            .getClaims()
            .getOrDefault(JwtClaim.EMAIL.key(), decodedJWT.getClaim(JwtClaim.SUBJECT.key()))
            .asString();

    LOGGER.debug("Validating principal from token claims: {}", subject);

    if ("admin".equals(subject)) {
      LOGGER.debug("admin always allowed");
      return subject;
    }

    try {
      User user = userRepository.getUserByEmail(subject);
      if (user != null && user.getState() == User.StateEnum.ENABLED) {
        LOGGER.debug("Principal {} is enabled", subject);
        return user.getEmail();
      }
    } catch (Exception e) {
      // IGNORE
    }

    throw new OAuthInvalidRequestException(
        ErrorCode.INVALID_ARGUMENT, "User not allowed: " + subject);
  }
}
