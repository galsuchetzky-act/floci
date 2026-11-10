package io.floci.aws.services.s3;

import io.floci.aws.core.common.RegionResolver;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(S3BucketRegionFallbackIntegrationTest.NonDefaultRegionProfile.class)
class S3BucketRegionFallbackIntegrationTest {
    public static class NonDefaultRegionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.default-region", "eu-central-1",
                    "floci.default-availability-zone", "eu-central-1a");
        }
    }

    @Inject S3Service service;
    @Inject RegionResolver regions;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void legacyRegionReportsAgree(String storedRegion) {
        assertEquals("eu-central-1", regions.getDefaultRegion());
        String bucket = "legacy-region-" + UUID.randomUUID();
        service.createBucket(bucket, storedRegion);
        try {
            assertReports(bucket, "eu-central-1");
        } finally {
            service.deleteBucket(bucket);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "ap-south-1"})
    void explicitRegionKeepsItsValueAndLocationEncoding(String region) {
        String bucket = "stored-region-" + UUID.randomUUID();
        service.createBucket(bucket, region);
        try {
            assertReports(bucket, region);
        } finally {
            service.deleteBucket(bucket);
        }
    }

    @Test
    void metadataReportingLeavesTheStoredRegionNullable() {
        String bucket = "nullable-region-" + UUID.randomUUID();
        service.createBucket(bucket, null);
        try {
            given().get("/" + bucket + "?location").then().statusCode(200);
            assertNull(service.getBucketRegion(bucket));
        } finally {
            service.deleteBucket(bucket);
        }
    }

    @Test
    void bareListingsStillOmitBucketRegion() {
        String bucket = "bare-region-" + UUID.randomUUID();
        service.createBucket(bucket, null);
        try {
            given().accept("application/xml").get("/").then().statusCode(200)
                    .body(not(containsString("<BucketRegion>")));
        } finally {
            service.deleteBucket(bucket);
        }
    }

    private static void assertReports(String bucket, String region) {
        given().head("/" + bucket).then().statusCode(200).header("x-amz-bucket-region", region);
        given().queryParam("max-buckets", "1000").get("/").then().statusCode(200)
                .body("ListAllMyBucketsResult.Buckets.Bucket.find { it.Name == '" + bucket + "' }.BucketRegion",
                        equalTo(region));
        String location = given().get("/" + bucket + "?location").then().statusCode(200)
                .extract().xmlPath().getString("LocationConstraint");
        assertEquals("us-east-1".equals(region) ? "" : region, location);
    }
}
