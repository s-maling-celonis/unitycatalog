package io.unitycatalog.server.service;

/** OAuth client credentials presented on a token exchange request. */
public record TokenExchangeClientCredentials(String clientId, String clientSecret) {}
