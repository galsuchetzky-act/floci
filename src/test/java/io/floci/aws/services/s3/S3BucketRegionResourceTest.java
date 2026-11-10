package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.resource.ExplorerResource;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.lambda.LambdaService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class S3BucketRegionResourceTest {
    @TempDir Path data;

    static Stream<Arguments> regions() {
        return Stream.of("eu-central-1", "us-gov-west-1", "cn-north-1").flatMap(region ->
                Arrays.asList(null, "", " ").stream().map(stored -> Arguments.of(region, stored)));
    }

    @ParameterizedTest
    @MethodSource("regions")
    void legacyResourcesUseTheDefaultRegionAndItsPartition(String defaultRegion, String storedRegion) {
        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), data, true,
                (LambdaService) null, new RegionResolver(defaultRegion, "000000000000"));
        String bucket = "resource-region-" + UUID.randomUUID();
        service.createBucket(bucket, storedRegion);
        ExplorerResource resource = assertDoesNotThrow(() -> service.getResources().stream()
                .filter(value -> value.arn().endsWith(":" + bucket)).findFirst().orElseThrow());
        assertEquals(defaultRegion, resource.region());
        assertEquals("arn:" + AwsRegions.partitionFor(defaultRegion) + ":s3:::" + bucket, resource.arn());
        assertEquals(storedRegion, service.getBucketRegion(bucket));
    }
}
