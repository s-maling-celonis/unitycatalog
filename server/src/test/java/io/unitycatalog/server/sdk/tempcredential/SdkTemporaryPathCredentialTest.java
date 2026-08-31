package io.unitycatalog.server.sdk.tempcredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.client.ApiException;
import io.unitycatalog.client.api.TemporaryCredentialsApi;
import io.unitycatalog.client.model.AwsS3AccessKeyRequest;
import io.unitycatalog.client.model.CreateCredentialRequest;
import io.unitycatalog.client.model.CreateExternalLocation;
import io.unitycatalog.client.model.CredentialPurpose;
import io.unitycatalog.client.model.GenerateTemporaryPathCredential;
import io.unitycatalog.client.model.PathOperation;
import io.unitycatalog.client.model.TemporaryCredentials;
import io.unitycatalog.server.base.BaseCRUDTestWithMockCredentials;
import io.unitycatalog.server.base.ServerConfig;
import io.unitycatalog.server.base.catalog.CatalogOperations;
import io.unitycatalog.server.base.schema.SchemaOperations;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.sdk.catalog.SdkCatalogOperations;
import io.unitycatalog.server.sdk.schema.SdkSchemaOperations;
import io.unitycatalog.server.utils.TestUtils;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SdkTemporaryPathCredentialTest extends BaseCRUDTestWithMockCredentials {
  private TemporaryCredentialsApi temporaryCredentialsApi;

  @Override
  protected CatalogOperations createCatalogOperations(ServerConfig serverConfig) {
    return new SdkCatalogOperations(TestUtils.createApiClient(serverConfig));
  }

  @Override
  protected SchemaOperations createSchemaOperations(ServerConfig serverConfig) {
    return new SdkSchemaOperations(TestUtils.createApiClient(serverConfig));
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    temporaryCredentialsApi = new TemporaryCredentialsApi(TestUtils.createApiClient(serverConfig));
  }

  @ParameterizedTest
  @MethodSource("getArgumentsForParameterizedTests")
  public void testGenerateTemporaryCredentialsWhereConfIsProvided(
      String scheme, boolean isConfiguredPath) throws ApiException {
    String url = getTestCloudPath(scheme, isConfiguredPath);
    GenerateTemporaryPathCredential generateTemporaryPathCredential =
        new GenerateTemporaryPathCredential().url(url).operation(PathOperation.PATH_READ);
    if (isConfiguredPath) {
      TemporaryCredentials temporaryCredentials =
          temporaryCredentialsApi.generateTemporaryPathCredentials(generateTemporaryPathCredential);
      assertTemporaryCredentials(temporaryCredentials, scheme, url);
    } else {
      assertThatThrownBy(
              () ->
                  temporaryCredentialsApi.generateTemporaryPathCredentials(
                      generateTemporaryPathCredential))
          .isInstanceOf(ApiException.class);
    }
  }

  @Test
  public void testGenerateAwsTemporaryCredentialsFromMasterRole() throws ApiException {
    for (String url : List.of(AWS_EXTERNAL_LOCATION_PATH, AWS_EXTERNAL_LOCATION_PATH + "/table1")) {
      GenerateTemporaryPathCredential generateTemporaryPathCredential =
          new GenerateTemporaryPathCredential().url(url).operation(PathOperation.PATH_READ_WRITE);
      TemporaryCredentials temporaryCredentials =
          temporaryCredentialsApi.generateTemporaryPathCredentials(generateTemporaryPathCredential);
      EchoAwsStsClient.assertAwsCredential(temporaryCredentials);
    }
    // Should fail because the path is not covered by external location
    TestUtils.assertApiException(
        () ->
            temporaryCredentialsApi.generateTemporaryPathCredentials(
                new GenerateTemporaryPathCredential()
                    .url(AWS_EXTERNAL_LOCATION_PARENT_PATH)
                    .operation(PathOperation.PATH_READ_WRITE)),
        ErrorCode.FAILED_PRECONDITION,
        "S3 bucket configuration not found");
  }

  @Test
  public void testGenerateStaticAwsCredentialsForS3AccessKey() throws ApiException {
    final String staticCredName = "static_aws_credential";
    final String staticLocName = "static_external_location";
    final String staticLocPath = "s3://static-external-location/data";
    final String accessKeyId = "staticAccessKey";

    credentialsApi.createCredential(
        new CreateCredentialRequest()
            .name(staticCredName)
            .purpose(CredentialPurpose.STORAGE)
            .awsS3AccessKey(new AwsS3AccessKeyRequest().accessKeyId(accessKeyId)));
    externalLocationsApi.createExternalLocation(
        new CreateExternalLocation()
            .name(staticLocName)
            .url(staticLocPath)
            .credentialName(staticCredName));

    TemporaryCredentials temporaryCredentials =
        temporaryCredentialsApi.generateTemporaryPathCredentials(
            new GenerateTemporaryPathCredential()
                .url(staticLocPath + "/table1")
                .operation(PathOperation.PATH_READ));

    assertThat(temporaryCredentials.getAwsTempCredentials().getAccessKeyId())
        .isEqualTo(accessKeyId);
    assertThat(temporaryCredentials.getAwsTempCredentials().getSecretAccessKey())
        .isEqualTo("staticSecretKey");
    assertThat(temporaryCredentials.getAwsTempCredentials().getSessionToken()).isNull();
    assertThat(temporaryCredentials.getExpirationTime()).isGreaterThan(System.currentTimeMillis());

    // GET must never expose a secret (only access key id is stored on the credential).
    assertThat(credentialsApi.getCredential(staticCredName).getAwsS3AccessKey().getAccessKeyId())
        .isEqualTo(accessKeyId);
  }
}
