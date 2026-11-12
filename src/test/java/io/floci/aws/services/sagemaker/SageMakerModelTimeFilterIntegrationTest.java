package io.floci.aws.services.sagemaker;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.sagemaker.model.ListModelsResponse;
import software.amazon.awssdk.services.sagemaker.model.SageMakerException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SageMakerModelTimeFilterIntegrationTest {
    @Test
    void sdkAppliesTimeFiltersAndTraversesTheirContinuation() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String first = "time-a-" + suffix;
        String second = "time-b-" + suffix;
        try (SageMakerClient client = AwsTestClients.create(SageMakerClient.builder(), "test", Region.US_EAST_1)) {
            for (String name : List.of(first, second)) {
                client.createModel(r -> r.modelName(name).primaryContainer(c -> c.image("busybox:stable")));
            }
            try {
                assertTrue(client.listModels(r -> r.nameContains(suffix).creationTimeBefore(Instant.EPOCH)
                        .maxResults(1)).models().isEmpty());
                assertTrue(client.listModels(r -> r.nameContains(suffix).creationTimeAfter(Instant.now().plusSeconds(3600))
                        .maxResults(1)).models().isEmpty());
                Instant created = client.describeModel(r -> r.modelName(first)).creationTime();
                assertTrue(client.listModels(r -> r.nameContains(first).creationTimeAfter(created)).hasModels());
                assertTrue(client.listModels(r -> r.nameContains(first).creationTimeBefore(created)).models().isEmpty());
                for (String sort : List.of("Name", "CreationTime")) {
                    ListModelsResponse page = client.listModels(r -> r.nameContains(suffix).sortBy(sort)
                            .sortOrder("Ascending").creationTimeAfter(Instant.EPOCH).maxResults(1));
                    assertEquals(1, page.models().size());
                    assertNotNull(page.nextToken());
                    ListModelsResponse next = client.listModels(r -> r.nameContains(suffix).sortBy(sort)
                            .sortOrder("Ascending").creationTimeAfter(Instant.EPOCH).maxResults(1).nextToken(page.nextToken()));
                    assertEquals(1, next.models().size());
                    assertNull(next.nextToken());
                    SageMakerException error = assertThrows(SageMakerException.class, () -> client.listModels(r -> r
                            .nameContains(suffix).sortBy(sort).sortOrder("Ascending").creationTimeAfter(Instant.ofEpochSecond(1))
                            .maxResults(1).nextToken(page.nextToken())));
                    assertEquals("ValidationException", error.awsErrorDetails().errorCode());
                }
            } finally {
                for (String name : List.of(first, second)) {
                    client.deleteModel(r -> r.modelName(name));
                }
            }
        }
    }
}
