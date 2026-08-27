package io.unitycatalog.server.utils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.server.exception.BaseException;
import org.junit.jupiter.api.Test;

public class S3LocationValidatorTest {

  @Test
  public void acceptsValidNestedPrefixLocations() {
    assertThatCode(() -> S3LocationValidator.validateCreateOrUpdate("s3://my-bucket/tenant-a/"))
        .doesNotThrowAnyException();
    assertThatCode(() -> S3LocationValidator.validateCreateOrUpdate("s3://my-bucket/tenant-a/data"))
        .doesNotThrowAnyException();
  }

  @Test
  public void ignoresNonS3Locations() {
    assertThatCode(() -> S3LocationValidator.validateCreateOrUpdate("gs://my-bucket/path"))
        .doesNotThrowAnyException();
    assertThatCode(() -> S3LocationValidator.validateCreateOrUpdate("file:///tmp/path"))
        .doesNotThrowAnyException();
  }

  @Test
  public void rejectsUppercaseBucketNames() {
    assertThatThrownBy(() -> S3LocationValidator.validateCreateOrUpdate("s3://My-Bucket/path"))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("must be lowercase");
  }

  @Test
  public void rejectsBucketNamesWithUnderscores() {
    assertThatThrownBy(() -> S3LocationValidator.validateCreateOrUpdate("s3://my_bucket/path"))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("must not contain underscores");
  }

  @Test
  public void rejectsMalformedBucketNames() {
    assertThatThrownBy(() -> S3LocationValidator.validateCreateOrUpdate("s3://ab/path"))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("DNS-compatible");
  }

  @Test
  public void rejectsBucketRootLocations() {
    assertThatThrownBy(() -> S3LocationValidator.validateCreateOrUpdate("s3://my-bucket"))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("non-empty path prefix");
  }

  @Test
  public void rejectsPrefixWildcards() {
    assertThatThrownBy(() -> S3LocationValidator.validateCreateOrUpdate("s3://my-bucket/a*"))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("wildcard characters");
    assertThatThrownBy(() -> S3LocationValidator.validateCreateOrUpdate("s3://my-bucket/a?b"))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("wildcard characters");
    assertThatThrownBy(() -> S3LocationValidator.validateCreateOrUpdate("s3://my-bucket/a$b"))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("wildcard characters");
  }

  @Test
  public void existingInvalidLocationsCanStillBeNormalizedForRead() {
    assertThatCode(() -> NormalizedURL.from("s3://My_Bucket/wild*card")).doesNotThrowAnyException();
  }
}
