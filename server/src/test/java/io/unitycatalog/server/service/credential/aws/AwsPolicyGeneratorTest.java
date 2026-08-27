package io.unitycatalog.server.service.credential.aws;

import static io.unitycatalog.server.service.credential.CredentialContext.Privilege.SELECT;
import static io.unitycatalog.server.service.credential.CredentialContext.Privilege.UPDATE;
import static io.unitycatalog.server.service.credential.aws.AwsPolicyGenerator.BUCKET_STATEMENT;
import static io.unitycatalog.server.service.credential.aws.AwsPolicyGenerator.KMS_STATEMENT;
import static io.unitycatalog.server.service.credential.aws.AwsPolicyGenerator.OPERATION_STATEMENT;
import static io.unitycatalog.server.service.credential.aws.AwsPolicyGenerator.POLICY_STATEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.unitycatalog.server.utils.NormalizedURL;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class AwsPolicyGeneratorTest {

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  @Test
  public void testPoliciesTemplatesYaml() {
    assertThatNoException().isThrownBy(() -> YAML_MAPPER.readTree(POLICY_STATEMENT));

    assertThatNoException().isThrownBy(() -> YAML_MAPPER.readTree(BUCKET_STATEMENT));

    assertThatNoException().isThrownBy(() -> YAML_MAPPER.readTree(OPERATION_STATEMENT));

    assertThatNoException().isThrownBy(() -> YAML_MAPPER.readTree(KMS_STATEMENT));
  }

  @SneakyThrows
  @Test
  public void testPolicySubstitution() {
    String bucket = "test-bucket";
    String prefix = "%s/%s".formatted(UUID.randomUUID(), UUID.randomUUID());
    NormalizedURL location = NormalizedURL.from("s3://%s/%s".formatted(bucket, prefix));

    String policy = AwsPolicyGenerator.generatePolicy(Set.of(SELECT), List.of(location));

    JsonNode node = JSON_MAPPER.readTree(policy);
    assertThat(node.get("Statement").get(1).get("Resource").get(0).asText())
        .isEqualTo("arn:aws:s3:::" + bucket);

    node.findPath("s3:prefix").forEach(e -> assertThat(e.asText()).startsWith(prefix));
  }

  @Test
  public void testWildcardPathDoesNotProduceBucketWidePolicy() throws Exception {
    // Policy generation still escapes wildcards for grandfathered locations already stored in
    // metadata. New S3 locations containing *, ?, or $ are rejected by S3LocationValidator.
    String bucket = "victim-bucket";
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(UPDATE), List.of(NormalizedURL.from("s3://%s/*".formatted(bucket))));

    JsonNode node = JSON_MAPPER.readTree(policy);
    assertThat(node.get("Statement").get(0).get("Resource"))
        .map(JsonNode::asText)
        .containsExactly(
            "arn:aws:s3:::%s/${*}/*".formatted(bucket), "arn:aws:s3:::%s/${*}".formatted(bucket))
        .doesNotContain(
            "arn:aws:s3:::%s/*/*".formatted(bucket), "arn:aws:s3:::%s/*".formatted(bucket));
    assertThat(node.get("Statement").get(1).findPath("s3:prefix"))
        .map(JsonNode::asText)
        .containsExactly("${*}", "${*}/", "${*}/*")
        .doesNotContain("*", "*/", "*/*");
  }

  @ParameterizedTest(name = "{index}: {0} becomes {1}")
  @CsvSource({
    "%2A, ${*}",
    "%2a, ${*}",
    "%3F, ${?}",
    "%3f, ${?}",
    "prefix*middle, prefix${*}middle",
    "prefix%3Fmiddle, prefix${?}middle",
    "%24%7Baws:username%7D, ${$}{aws:username}",
    "$%7B*%7D, ${$}{${*}}"
  })
  public void testPolicyEscapesIamSpecialCharacters(String encodedPath, String expectedPath)
      throws Exception {
    // Exercised for legacy stored paths; S3LocationValidator rejects these on create/update.
    String bucket = "victim-bucket";
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(UPDATE),
            List.of(NormalizedURL.from("s3://%s/%s".formatted(bucket, encodedPath))));

    JsonNode node = JSON_MAPPER.readTree(policy);
    assertThat(node.get("Statement").get(0).get("Resource"))
        .map(JsonNode::asText)
        .containsExactly(
            "arn:aws:s3:::%s/%s/*".formatted(bucket, expectedPath),
            "arn:aws:s3:::%s/%s".formatted(bucket, expectedPath));
    assertThat(node.get("Statement").get(1).findPath("s3:prefix"))
        .map(JsonNode::asText)
        .containsExactly(expectedPath, expectedPath + "/", expectedPath + "/*");
  }

  @Test
  public void testPolicyLeavesOrdinaryPathUnchanged() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(SELECT), List.of(NormalizedURL.from("s3://my-bucket/path1/table1")));

    JsonNode node = JSON_MAPPER.readTree(policy);
    assertThat(node.get("Statement").get(0).get("Resource"))
        .map(JsonNode::asText)
        .containsExactly(
            "arn:aws:s3:::my-bucket/path1/table1/*", "arn:aws:s3:::my-bucket/path1/table1");
    assertThat(node.get("Statement").get(1).findPath("s3:prefix"))
        .map(JsonNode::asText)
        .containsExactly("path1/table1", "path1/table1/", "path1/table1/*");
  }

  @Test
  public void testPolicyPreservesIntentionalBucketRootWildcard() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(SELECT), List.of(NormalizedURL.from("s3://my-bucket")));

    JsonNode node = JSON_MAPPER.readTree(policy);
    assertThat(node.get("Statement").get(0).get("Resource"))
        .map(JsonNode::asText)
        .containsExactly("arn:aws:s3:::my-bucket/*");
    assertThat(node.get("Statement").get(1).findPath("s3:prefix"))
        .map(JsonNode::asText)
        .containsExactly("*");
  }

  @Test
  public void testPolicyWithStorageProfileLocations() {
    String updatePolicy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(SELECT, UPDATE),
            Stream.of(
                    "s3://my-bucket1/path1/table1", "s3://profile-bucket2/", "s3://profile-bucket3")
                .map(NormalizedURL::from)
                .toList());

    assertThat(updatePolicy)
        .contains("s3:PutObject")
        .contains("s3:GetObject")
        .contains("s3:DeleteObject")
        .contains("s3:AbortMultipartUpload")
        .contains("s3:ListMultipartUploadParts")
        .doesNotContain("s3:PutO*")
        .doesNotContain("s3:GetO*")
        .doesNotContain("s3:DeleteO*")
        .doesNotContain("s3:*Multipart*")
        .contains("arn:aws:s3:::profile-bucket2/*")
        .contains("arn:aws:s3:::profile-bucket3/*");

    String selectPolicy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(SELECT),
            Stream.of(
                    "s3://my-bucket1/path1/table1",
                    "s3://my-bucket2/path2/table2",
                    "s3://my-bucket1/path3/table3")
                .map(NormalizedURL::from)
                .toList());

    assertThat(selectPolicy)
        .doesNotContain("s3:PutObject")
        .doesNotContain("s3:DeleteObject")
        .doesNotContain("s3:PutO*")
        .doesNotContain("s3:DeleteO*")
        .contains("s3:GetObject")
        .doesNotContain("s3:GetO*");
  }

  @Test
  public void testSelectPolicyGrantsKmsDecrypt() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(SELECT), List.of(NormalizedURL.from("s3://my-bucket/path1/table1")));

    JsonNode statement = findKmsStatement(JSON_MAPPER.readTree(policy));
    assertThat(statement).isNotNull();
    assertThat(statement.path("Action")).map(JsonNode::asText).containsExactly("kms:Decrypt");
    assertThat(statement.path("Resource")).map(JsonNode::asText).containsExactly("*");
    assertThat(statement.findPath("kms:ViaService").asText()).isEqualTo("s3.*.amazonaws.com");
  }

  @Test
  public void testUpdatePolicyGrantsKmsDecryptAndGenerateDataKey() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(UPDATE), List.of(NormalizedURL.from("s3://my-bucket/path1/table1")));

    JsonNode statement = findKmsStatement(JSON_MAPPER.readTree(policy));
    assertThat(statement).isNotNull();
    assertThat(statement.path("Action"))
        .map(JsonNode::asText)
        .containsExactlyInAnyOrder("kms:Decrypt", "kms:GenerateDataKey*");
    assertThat(statement.path("Resource")).map(JsonNode::asText).containsExactly("*");
    assertThat(statement.findPath("kms:ViaService").asText()).isEqualTo("s3.*.amazonaws.com");
  }

  @Test
  public void testKmsStatementOmitsEncryptionContext() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(UPDATE), List.of(NormalizedURL.from("s3://my-bucket/path1/table1")));

    JsonNode statement = findKmsStatement(JSON_MAPPER.readTree(policy));
    assertThat(statement.findPath("kms:ViaService").asText()).isEqualTo("s3.*.amazonaws.com");
    assertThat(policy).doesNotContain("kms:EncryptionContext");
  }

  @Test
  public void testLongGovCloudManagedTablePathFitsStsSessionPolicyLimit() {
    String location =
        "s3://storium-usgov-celonisoutpost-com/"
            + "2810ce0f-866e-42a2-88a2-c1abcefa4807_datalake_unity/"
            + "__unitystorage/schemas/c800dd5b-c4f5-48ae-ac0f-87cd5be7fce7/"
            + "tables/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(UPDATE), List.of(NormalizedURL.from(location)), true, "us-gov-west-1");

    // STS rejects AssumeRole when the packed session policy hits 100%. The plaintext limit is
    // 2048 characters; staying well under that keeps packed size in budget on long usgov paths.
    assertThat(policy.length()).isLessThan(1600);
    assertThat(policy).doesNotContain("kms:EncryptionContext");
  }

  @Test
  public void testKmsStatementDoesNotShiftS3Statements() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(SELECT), List.of(NormalizedURL.from("s3://my-bucket/path1/table1")));

    JsonNode statements = JSON_MAPPER.readTree(policy).get("Statement");
    assertThat(statements.get(0).path("Action"))
        .map(JsonNode::asText)
        .containsExactly("s3:GetObject");
    assertThat(statements.get(1).path("Action"))
        .map(JsonNode::asText)
        .containsExactly("s3:ListBucket");
  }

  @ParameterizedTest(name = "{index}: region {0} -> partition {1}")
  @CsvSource({
    "us-east-1, aws",
    "us-west-2, aws",
    "us-gov-west-1, aws-us-gov",
    "us-gov-east-1, aws-us-gov",
    "cn-north-1, aws-cn",
    "cn-northwest-1, aws-cn",
    "'', aws"
  })
  public void testS3PartitionIdFromRegion(String region, String expectedPartition) {
    assertThat(AwsPolicyGenerator.s3PartitionId(region)).isEqualTo(expectedPartition);
  }

  @Test
  public void testNullRegionDefaultsToAwsPartition() {
    assertThat(AwsPolicyGenerator.s3PartitionId(null)).isEqualTo("aws");
  }

  @ParameterizedTest(name = "{index}: {0} uses {1}")
  @CsvSource({
    "us-east-1, arn:aws:s3:::my-bucket",
    "us-gov-west-1, arn:aws-us-gov:s3:::my-bucket",
    "cn-north-1, arn:aws-cn:s3:::my-bucket",
    "'', arn:aws:s3:::my-bucket"
  })
  public void testSessionPolicyS3ArnsUseRegionPartition(String region, String expectedBucketArn)
      throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(SELECT),
            List.of(NormalizedURL.from("s3://my-bucket/path1/table1")),
            true,
            region);

    JsonNode root = JSON_MAPPER.readTree(policy);
    assertThat(root.get("Statement").get(0).get("Resource"))
        .map(JsonNode::asText)
        .containsExactly(
            expectedBucketArn + "/path1/table1/*", expectedBucketArn + "/path1/table1");
    assertThat(root.get("Statement").get(1).get("Resource").get(0).asText())
        .isEqualTo(expectedBucketArn);
    assertThat(findKmsStatement(root).findPath("kms:ViaService").asText())
        .isEqualTo("s3.*.amazonaws.com");
    assertThat(policy).doesNotContain("kms:EncryptionContext");
  }

  @Test
  public void testGovCloudPolicyDoesNotEmitCommercialS3Arns() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(UPDATE),
            List.of(NormalizedURL.from("s3://gov-bucket/path/table")),
            true,
            "us-gov-west-1");

    assertThat(policy)
        .contains("arn:aws-us-gov:s3:::gov-bucket")
        .doesNotContain("arn:aws:s3:::gov-bucket");
    JsonNode statement = findKmsStatement(JSON_MAPPER.readTree(policy));
    assertThat(statement.findPath("kms:ViaService").asText()).isEqualTo("s3.*.amazonaws.com");
  }

  @Test
  public void testGeneratePolicyOmitsKmsWhenDisabled() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(UPDATE), List.of(NormalizedURL.from("s3://my-bucket/path1/table1")), false);

    JsonNode root = JSON_MAPPER.readTree(policy);
    assertThat(findKmsStatement(root)).isNull();
    assertThat(policy)
        .doesNotContain("kms:ViaService")
        .doesNotContain("kms:EncryptionContext")
        .contains("s3:PutObject");
    assertThat(root.get("Statement")).hasSize(2);
  }

  @Test
  public void testUpdatePolicyEmitsExactMultipartActions() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(UPDATE), List.of(NormalizedURL.from("s3://my-bucket/path1/table1")));

    JsonNode actions = JSON_MAPPER.readTree(policy).get("Statement").get(0).path("Action");
    assertThat(actions)
        .map(JsonNode::asText)
        .containsExactlyInAnyOrder(
            "s3:GetObject",
            "s3:PutObject",
            "s3:DeleteObject",
            "s3:AbortMultipartUpload",
            "s3:ListMultipartUploadParts");
  }

  @Test
  public void testGeneratedPolicyJsonIsCompact() throws Exception {
    String policy =
        AwsPolicyGenerator.generatePolicy(
            Set.of(SELECT), List.of(NormalizedURL.from("s3://my-bucket/path1/table1")));

    assertThat(policy).doesNotContain("\n").doesNotContain("  ");
  }

  @Test
  public void testBlankStsEndpointIsTreatedAsAws() {
    assertThat(AwsPolicyGenerator.stsSupportsKmsPolicyConditions(null)).isTrue();
    assertThat(AwsPolicyGenerator.stsSupportsKmsPolicyConditions("")).isTrue();
    assertThat(AwsPolicyGenerator.stsSupportsKmsPolicyConditions("   ")).isTrue();
  }

  @ParameterizedTest(name = "{index}: {0} -> {1}")
  @CsvSource({
    "https://sts.us-east-1.amazonaws.com, true",
    "https://sts.us-east-1.amazonaws.com.cn, true",
    "https://vpce-123.sts.us-east-1.vpce.amazonaws.com, true",
    "http://minio:9000, false",
    "http://localhost:9000, false",
    "http://127.0.0.1:9000, false",
    "http://host.docker.internal:9000, false"
  })
  public void testStsEndpointKmsConditionSupport(String endpoint, boolean expected) {
    assertThat(AwsPolicyGenerator.stsSupportsKmsPolicyConditions(endpoint)).isEqualTo(expected);
  }

  /**
   * Returns the statement that carries the KMS actions, or {@code null} when the policy has none.
   * Located by action prefix rather than by index so that the S3 statements the other tests assert
   * on by index stay where they are.
   */
  private static JsonNode findKmsStatement(JsonNode policyRoot) {
    for (JsonNode statement : policyRoot.path("Statement")) {
      for (JsonNode action : statement.path("Action")) {
        if (action.asText().startsWith("kms:")) {
          return statement;
        }
      }
    }
    return null;
  }
}
