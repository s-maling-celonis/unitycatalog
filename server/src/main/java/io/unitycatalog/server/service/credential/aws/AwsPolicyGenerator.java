package io.unitycatalog.server.service.credential.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.utils.NormalizedURL;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.RegionMetadata;

public class AwsPolicyGenerator {

  static final List<String> SELECT_ACTIONS = List.of("s3:GetObject");
  static final List<String> UPDATE_ACTIONS =
      List.of(
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:AbortMultipartUpload",
          "s3:ListMultipartUploadParts");

  // Reading an object encrypted with SSE-KMS requires kms:Decrypt, and writing one additionally
  // requires kms:GenerateDataKey*. Without these the vended session credentials can't touch a
  // table stored in a bucket with SSE-KMS, even when the assumed role itself is allowed to.
  static final List<String> SELECT_KMS_ACTIONS = List.of("kms:Decrypt");
  static final List<String> UPDATE_KMS_ACTIONS = List.of("kms:Decrypt", "kms:GenerateDataKey*");

  static final String POLICY_STATEMENT =
      """
      Version: 2012-10-17
      Statement: []
      """;

  static final String BUCKET_STATEMENT =
      """
      Effect: Allow
      Action:
        - s3:ListBucket
      Resource: []
      Condition:
        StringLike:
          "s3:prefix": []
      """;

  static final String OPERATION_STATEMENT =
      """
      Effect: Allow
      Action: []
      Resource: []
      """;

  // Unity Catalog doesn't know which KMS key a bucket is configured with, so the resource stays
  // open and the statement is narrowed to KMS calls made through S3. A session policy can only
  // narrow what the assumed role is already allowed to do, so a role without KMS access still
  // gets none. Encryption-context ARNs are omitted on purpose: they duplicate every S3 resource
  // in this policy, and on long GovCloud managed-table paths that blows the STS packed-policy
  // limit ("Packed policy consumes 100% of allotted space"). S3 resource statements already
  // constrain which objects the session can Get/Put, so the extra KMS scoping is redundant.
  static final String KMS_STATEMENT =
      """
      Effect: Allow
      Action: []
      Resource:
        - "*"
      Condition:
        StringLike:
          "kms:ViaService": "s3.*.amazonaws.com"
      """;

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  /**
   * AWS IAM KMS condition keys ({@code kms:ViaService}) are rejected by S3-compatible STS
   * implementations such as MinIO ({@code invalid condition key}). Only attach the KMS statement
   * when AssumeRole targets AWS STS.
   *
   * <p>A blank endpoint means the AWS SDK default (real AWS STS). Hosts under {@code amazonaws.com}
   * / {@code amazonaws.com.cn} are treated as AWS; everything else (MinIO, localhost, custom
   * gateways) is not.
   */
  public static boolean stsSupportsKmsPolicyConditions(String stsEndpointUrl) {
    if (stsEndpointUrl == null || stsEndpointUrl.isBlank()) {
      return true;
    }
    URI uri;
    try {
      uri = URI.create(stsEndpointUrl.trim());
    } catch (IllegalArgumentException ignored) {
      return false;
    }
    String host = uri.getHost();
    if (host == null) {
      return false;
    }
    String normalized = host.toLowerCase(Locale.ROOT);
    return normalized.equals("amazonaws.com")
        || normalized.endsWith(".amazonaws.com")
        || normalized.equals("amazonaws.com.cn")
        || normalized.endsWith(".amazonaws.com.cn");
  }

  // This can support generating a policy across multiple buckets and paths, however, the assumed
  // role the policy is applied to for a scoped-session needs to have access across those buckets
  @SneakyThrows
  public static String generatePolicy(
      Set<CredentialContext.Privilege> privileges, List<NormalizedURL> locations) {
    return generatePolicy(privileges, locations, true);
  }

  @SneakyThrows
  public static String generatePolicy(
      Set<CredentialContext.Privilege> privileges,
      List<NormalizedURL> locations,
      boolean includeKmsPermissions) {
    return generatePolicy(privileges, locations, includeKmsPermissions, null);
  }

  /**
   * Builds the AssumeRole session policy for the given locations.
   *
   * <p>{@code awsRegion} selects the S3 ARN partition ({@code aws}, {@code aws-us-gov}, {@code
   * aws-cn}) via the AWS SDK region catalog. A blank region keeps the commercial {@code aws}
   * partition, matching the previous hardcoded ARNs.
   */
  @SneakyThrows
  public static String generatePolicy(
      Set<CredentialContext.Privilege> privileges,
      List<NormalizedURL> locations,
      boolean includeKmsPermissions,
      String awsRegion) {
    String partition = s3PartitionId(awsRegion);
    JsonNode policyRoot = loadYaml(POLICY_STATEMENT);
    ArrayNode policyStatement = (ArrayNode) policyRoot.findPath("Statement");
    JsonNode operationsStatement = loadYaml(OPERATION_STATEMENT);
    policyStatement.add(operationsStatement);
    JsonNode kmsStatement = includeKmsPermissions ? loadYaml(KMS_STATEMENT) : null;

    // Add the appropriate S3 and KMS operations for the privileges requested
    ArrayNode actions = (ArrayNode) operationsStatement.findPath("Action");
    ArrayNode kmsActions =
        kmsStatement != null ? (ArrayNode) kmsStatement.findPath("Action") : null;
    if (privileges.contains(CredentialContext.Privilege.UPDATE)) {
      UPDATE_ACTIONS.forEach(actions::add);
      if (kmsActions != null) {
        UPDATE_KMS_ACTIONS.forEach(kmsActions::add);
      }
    } else if (privileges.contains(CredentialContext.Privilege.SELECT)) {
      SELECT_ACTIONS.forEach(actions::add);
      if (kmsActions != null) {
        SELECT_KMS_ACTIONS.forEach(kmsActions::add);
      }
    } else {
      throw new NotAuthorizedException(
          String.format(
              "Can't generate policy for unknown privileges '%s' for locations: '%s'",
              privileges, locations));
    }

    // Group each location by s3 bucket it's located in, then for each
    // bucket, add the bucket arn for the listBucket and operations statements,
    // then add each path as a conditional prefix
    getBucketToPathsMap(locations)
        .forEach(
            (bucketName, paths) -> {
              JsonNode listStatement = loadYaml(BUCKET_STATEMENT);
              policyStatement.add(listStatement);

              ArrayNode bucketResource = (ArrayNode) listStatement.findPath("Resource");
              ArrayNode operationsResource = (ArrayNode) operationsStatement.findPath("Resource");
              bucketResource.add(s3Arn(partition, bucketName));

              ArrayNode conditionalPrefixes = (ArrayNode) listStatement.findPath("s3:prefix");
              paths.forEach(
                  path -> {
                    // remove any preceding forward slashes
                    String sanitizedPath = escapeIamSpecialCharacters(path.replaceAll("^/+", ""));

                    if (sanitizedPath.isEmpty()) {
                      conditionalPrefixes.add("*");
                      operationsResource.add(s3Arn(partition, bucketName + "/*"));
                    } else {
                      conditionalPrefixes.add(sanitizedPath);
                      conditionalPrefixes.add(sanitizedPath + "/");
                      conditionalPrefixes.add(sanitizedPath + "/*");

                      operationsResource.add(
                          s3Arn(partition, bucketName + "/" + sanitizedPath + "/*"));
                      operationsResource.add(s3Arn(partition, bucketName + "/" + sanitizedPath));
                    }
                  });
            });

    // Appended after the per-bucket statements so that the position of the S3 statements
    // within the policy doesn't change
    if (kmsStatement != null) {
      policyStatement.add(kmsStatement);
    }

    return JSON_MAPPER.writeValueAsString(policyRoot);
  }

  /**
   * IAM partition for S3 ARNs in the session policy. Derived from the AWS SDK region catalog so
   * GovCloud ({@code us-gov-*}) yields {@code aws-us-gov} and China ({@code cn-*}) yields {@code
   * aws-cn}. A blank or unrecognized region keeps {@code aws}, which is what the policy used before
   * this was parameterized.
   */
  static String s3PartitionId(String awsRegion) {
    if (awsRegion == null || awsRegion.isBlank()) {
      return "aws";
    }
    try {
      RegionMetadata metadata = Region.of(awsRegion).metadata();
      if (metadata == null || metadata.partition() == null) {
        return "aws";
      }
      String partitionId = metadata.partition().id();
      return partitionId == null || partitionId.isBlank() ? "aws" : partitionId;
    } catch (RuntimeException e) {
      return "aws";
    }
  }

  private static String s3Arn(String partition, String resource) {
    return String.format("arn:%s:s3:::%s", partition, resource);
  }

  /**
   * Makes an S3 path safe to include in an IAM policy.
   *
   * <p>S3 treats {@code *}, {@code ?}, and {@code $} as ordinary characters in object names. IAM
   * gives them special meanings:
   *
   * <ul>
   *   <li>{@code *} matches any number of characters. For example, {@code reports/*} matches every
   *       object under {@code reports/}.
   *   <li>{@code ?} matches exactly one character. For example, {@code file?.txt} matches {@code
   *       file1.txt}.
   *   <li>{@code ${...}} is a policy variable whose value IAM fills in when it evaluates the
   *       policy. For example, {@code ${aws:username}} is replaced with the caller's IAM username.
   * </ul>
   *
   * <p>Therefore, copying an S3 path directly into a policy could grant access to more objects than
   * the path names. AWS provides {@code ${*}}, {@code ${?}}, and {@code ${$}} to make IAM match the
   * literal {@code *}, {@code ?}, and {@code $} characters instead.
   *
   * <p>We replace {@code $} first because the replacements for {@code *} and {@code ?} also contain
   * a dollar sign.
   *
   * @see <a
   *     href="https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_variables.html">
   *     AWS documentation for IAM policy variables</a>
   */
  private static String escapeIamSpecialCharacters(String keyPrefix) {
    return keyPrefix.replace("$", "${$}").replace("*", "${*}").replace("?", "${?}");
  }

  private static Map<String, List<String>> getBucketToPathsMap(List<NormalizedURL> locations) {
    return locations.stream()
        .map(NormalizedURL::toUri)
        .collect(
            Collectors.toMap(
                URI::getHost,
                uri -> new LinkedList<>(List.of(uri.getPath())),
                (map, newPaths) -> {
                  map.addAll(newPaths);
                  return map;
                }));
  }

  @SneakyThrows
  private static JsonNode loadYaml(String s) {
    return YAML_MAPPER.readTree(s);
  }
}
