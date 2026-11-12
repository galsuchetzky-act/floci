package io.floci.aws.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointConfigResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.ModelResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SageMakerModelTimeFilterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final InMemoryStorage<String, ModelResource> models = new InMemoryStorage<>();
    private final SageMakerService service = new SageMakerService(models,
            new InMemoryStorage<String, EndpointConfigResource>(),
            new InMemoryStorage<String, EndpointResource>(),
            new InMemoryStorage<String, TrainingJobResource>(),
            new RegionResolver("us-east-1", "000000000000"), mapper,
            mock(SageMakerEndpointManager.class), mock(SageMakerTrainingRunner.class));

    private ObjectNode request() {
        return mapper.createObjectNode().put("SortOrder", "Ascending");
    }

    private void seed() {
        put("a", 1000);
        put("b", 2000);
        put("c", 2001);
        put("d", 3000);
    }

    private void put(String name, long time) {
        ModelResource model = new ModelResource();
        model.modelName = name;
        model.creationTime = time;
        models.put("us-east-1::" + name, model);
    }

    private List<String> names(JsonNode response) {
        List<String> result = new ArrayList<>();
        response.path("Models").forEach(model -> result.add(model.path("ModelName").asText()));
        return result;
    }

    @Test
    void beforeIsExclusiveAndAfterIsInclusive() {
        seed();
        assertEquals(List.of("a"), names(service.listModels(request().put("CreationTimeBefore", 2), "us-east-1")));
        assertEquals(List.of("b", "c", "d"), names(service.listModels(request().put("CreationTimeAfter", 2), "us-east-1")));
    }

    @Test
    void subMillisecondBoundsKeepTheirPrecision() {
        seed();
        assertEquals(List.of("a", "b"), names(service.listModels(request()
                .put("CreationTimeBefore", new BigDecimal("2.0005")), "us-east-1")));
        assertEquals(List.of("c", "d"), names(service.listModels(request()
                .put("CreationTimeAfter", new BigDecimal("2.0005")), "us-east-1")));
    }

    @Test
    void filtersAreIntersectedBeforePaging() {
        seed();
        ObjectNode input = request().put("CreationTimeAfter", 2).put("CreationTimeBefore", 3).put("MaxResults", 1);
        JsonNode first = service.listModels(input, "us-east-1");
        assertEquals(List.of("b"), names(first));
        JsonNode second = service.listModels(input.put("NextToken", first.path("NextToken").asText()), "us-east-1");
        assertEquals(List.of("c"), names(second));
        assertFalse(second.has("NextToken"));
    }

    @Test
    void emptyAndReversedWindowsHaveNoContinuation() {
        seed();
        for (ObjectNode input : List.of(request().put("CreationTimeBefore", 0),
                request().put("CreationTimeAfter", 4),
                request().put("CreationTimeAfter", 3).put("CreationTimeBefore", 2))) {
            JsonNode result = service.listModels(input.put("MaxResults", 1), "us-east-1");
            assertEquals(List.of(), names(result));
            assertFalse(result.has("NextToken"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Name", "CreationTime"})
    void changedTimeFiltersInvalidateContinuations(String sortBy) {
        seed();
        ObjectNode input = request().put("SortBy", sortBy).put("CreationTimeAfter", 1).put("MaxResults", 1);
        String token = service.listModels(input, "us-east-1").path("NextToken").asText();
        for (ObjectNode changed : List.of(input.deepCopy().put("CreationTimeAfter", 2),
                input.deepCopy().put("CreationTimeBefore", 3), request().put("SortBy", sortBy))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.listModels(changed.put("NextToken", token), "us-east-1"));
            assertEquals("ValidationException", error.getErrorCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Name", "CreationTime"})
    void equivalentTimeFiltersResumeAfterDeletedAnchor(String sortBy) {
        seed();
        ObjectNode input = request().put("SortBy", sortBy).put("CreationTimeAfter", 2).put("MaxResults", 1);
        String token = service.listModels(input, "us-east-1").path("NextToken").asText();
        models.delete("us-east-1::b");
        assertEquals(List.of("c"), names(service.listModels(input.put("NextToken", token)
                .put("CreationTimeAfter", new BigDecimal("2.000")), "us-east-1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreationTimeBefore", "CreationTimeAfter"})
    void invalidTimestampTypesAreRejected(String field) {
        seed();
        for (JsonNode invalid : List.of(mapper.getNodeFactory().textNode("2"),
                mapper.getNodeFactory().booleanNode(true), mapper.createArrayNode(), mapper.createObjectNode())) {
            ObjectNode input = request();
            input.set(field, invalid);
            AwsException error = assertThrows(AwsException.class, () -> service.listModels(input, "us-east-1"));
            assertEquals("ValidationException", error.getErrorCode());
        }
    }

    @Test
    void omittedAndNullFiltersPreserveExistingOrderAndNameCursor() {
        seed();
        ObjectNode input = request().put("SortBy", "Name").put("MaxResults", 1).putNull("CreationTimeBefore");
        JsonNode first = service.listModels(input, "us-east-1");
        assertEquals("a", first.path("NextToken").asText());
        assertEquals(List.of("b"), names(service.listModels(input.put("NextToken", "a"), "us-east-1")));
    }
}
