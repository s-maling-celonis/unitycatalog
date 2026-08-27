package io.unitycatalog.server.utils;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import java.util.regex.Pattern;

/**
 * Validates S3 storage locations on create and update.
 *
 * <p>Existing locations stored before these rules were introduced are not re-validated on read or
 * credential vending; only new and updated locations are rejected.
 */
public final class S3LocationValidator {

  private static final String S3_SCHEME_PREFIX = "s3://";

  /**
   * DNS-compliant S3 bucket naming rules: 3–63 chars, lowercase letters, digits, dots, and hyphens,
   * starting and ending with a letter or digit, not an IPv4 address, and no underscores.
   */
  private static final Pattern DNS_BUCKET_NAME =
      Pattern.compile("^(?!(?:\\d{1,3}\\.){3}\\d{1,3}$)[a-z0-9](?:[a-z0-9.-]{1,61}[a-z0-9])?$");

  private S3LocationValidator() {}

  /**
   * Validates an S3 location supplied on create or update. Non-S3 schemes are ignored.
   *
   * @param location normalized storage location
   */
  public static void validateCreateOrUpdate(NormalizedURL location) {
    if (location == null) {
      return;
    }
    if (UriScheme.fromURI(location.toUri()) != UriScheme.S3) {
      return;
    }
    validateS3Location(location.toString());
  }

  /**
   * Validates an S3 location string on create or update. Non-S3 schemes are ignored.
   *
   * @param location raw storage location before or after normalization
   */
  public static void validateCreateOrUpdate(String location) {
    if (location == null || location.isBlank()) {
      return;
    }
    String trimmed = location.trim();
    if (!trimmed.toLowerCase().startsWith(S3_SCHEME_PREFIX)) {
      return;
    }
    validateS3Location(trimmed);
  }

  private static void validateS3Location(String location) {
    ParsedS3Location parsed = parseS3Location(location);
    String bucket = parsed.bucket();
    if (bucket.isBlank()) {
      throw invalidLocation(location, "S3 URI must include a bucket name");
    }
    if (!bucket.equals(bucket.toLowerCase())) {
      throw invalidLocation(location, "S3 bucket name must be lowercase");
    }
    if (bucket.contains("_")) {
      throw invalidLocation(location, "S3 bucket name must not contain underscores");
    }
    if (!DNS_BUCKET_NAME.matcher(bucket).matches()) {
      throw invalidLocation(location, "S3 bucket name must be DNS-compatible");
    }

    String prefix = parsed.prefix();
    if (prefix.isEmpty()) {
      throw invalidLocation(
          location, "S3 location must include a non-empty path prefix before use");
    }
    if (prefix.indexOf('*') >= 0 || prefix.indexOf('?') >= 0 || prefix.indexOf('$') >= 0) {
      throw invalidLocation(
          location, "S3 path prefix must not contain wildcard characters (*, ?, or $)");
    }
  }

  /**
   * Parses {@code s3://bucket/prefix} from the raw string so validation sees characters such as
   * {@code ?} in the prefix instead of treating them as URI query delimiters.
   */
  private static ParsedS3Location parseS3Location(String location) {
    if (!location.regionMatches(true, 0, S3_SCHEME_PREFIX, 0, S3_SCHEME_PREFIX.length())) {
      throw invalidLocation(location, "malformed S3 URI");
    }
    String remainder = location.substring(S3_SCHEME_PREFIX.length());
    int slash = remainder.indexOf('/');
    if (slash < 0) {
      return new ParsedS3Location(remainder, "");
    }
    String bucket = remainder.substring(0, slash);
    String prefix = remainder.substring(slash + 1);
    while (prefix.startsWith("/")) {
      prefix = prefix.substring(1);
    }
    while (prefix.endsWith("/")) {
      prefix = prefix.substring(0, prefix.length() - 1);
    }
    return new ParsedS3Location(bucket, prefix);
  }

  private record ParsedS3Location(String bucket, String prefix) {}

  private static BaseException invalidLocation(String location, String reason) {
    return new BaseException(
        ErrorCode.INVALID_ARGUMENT,
        String.format("Invalid S3 location '%s': %s", location, reason));
  }
}
