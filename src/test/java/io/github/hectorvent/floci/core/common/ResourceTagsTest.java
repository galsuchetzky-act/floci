package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceTagsTest {

    private static final String QUEUE = "arn:aws:sqs:us-east-1:000000000000:orders";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:alerts";

    @Test
    void theTagsOfTheResourceAreConditionKeys() {
        ResourceTags tags = new ResourceTags(List.of(handler("sqs", arn -> Map.of("team", "red"))), List.of());
        assertEquals(Map.of("aws:ResourceTag/team", List.of("red")),
                tags.conditionKeys("sqs:SendMessage", QUEUE, null, "us-east-1"));
    }

    @Test
    void aReaderAnswersForItsService() {
        ResourceTags tags = new ResourceTags(List.of(), List.of(reader("sns", arn -> Map.of("team", "blue"))));
        assertEquals(Map.of("aws:ResourceTag/team", List.of("blue")),
                tags.conditionKeys("sns:Publish", TOPIC, null, "us-east-1"));
    }

    @Test
    void anActionThatDoesNotTakeTheKeyOrNoSingleResourceGetsNone() {
        ResourceTags tags = new ResourceTags(List.of(handler("sqs", arn -> Map.of("team", "red"))), List.of());
        assertTrue(tags.conditionKeys("sqs:ListQueues", QUEUE, null, "us-east-1").isEmpty());
        assertTrue(tags.conditionKeys("sqs:SendMessage", "arn:aws:sqs:us-east-1:000000000000:*", null,
                "us-east-1").isEmpty());
        assertTrue(tags.conditionKeys("sqs:SendMessage", "*", null, "us-east-1").isEmpty());
        assertTrue(tags.conditionKeys("sqs:SendMessage", "arn:aws:sqs:us-east-1:000000000000:orders-??????", null,
                "us-east-1").isEmpty());
    }

    @Test
    void aResourceThatDoesNotExistHasNoTags() {
        ResourceTags tags = new ResourceTags(List.of(handler("sqs", arn -> {
            throw new AwsException("AWS.SimpleQueueService.NonExistentQueue", "The specified queue does not exist.", 400);
        })), List.of());
        assertTrue(tags.conditionKeys("sqs:SendMessage", QUEUE, null, "us-east-1").isEmpty());
    }

    @Test
    void aLookupThatFailsOtherwiseFailsTheRequest() {
        AwsException outage = new AwsException("InternalFailure", "storage unavailable", 500);
        ResourceTags failing = new ResourceTags(List.of(handler("sqs", arn -> {
            throw outage;
        })), List.of());
        assertSame(outage, assertThrows(AwsException.class,
                () -> failing.conditionKeys("sqs:SendMessage", QUEUE, null, "us-east-1")));

        ResourceTags broken = new ResourceTags(List.of(handler("sqs", arn -> {
            throw new IllegalStateException("corrupt record");
        })), List.of());
        assertThrows(IllegalStateException.class,
                () -> broken.conditionKeys("sqs:SendMessage", QUEUE, null, "us-east-1"));
    }

    @Test
    void contextsAlreadyCarryingAResourceTagAreRecognised() {
        assertTrue(ResourceTags.hasResourceTags(Map.of("aws:ResourceTag/team", List.of("red"))));
        assertEquals(false, ResourceTags.hasResourceTags(Map.of("aws:RequestTag/team", List.of("red"))));
        assertEquals(false, ResourceTags.hasResourceTags(null));
    }

    private static TagHandler handler(String service, Function<String, Map<String, String>> tags) {
        return new TagHandler() {
            @Override
            public String serviceKey() {
                return service;
            }

            @Override
            public Map<String, String> listTags(String region, String arn) {
                return tags.apply(arn);
            }

            @Override
            public void tagResource(String region, String arn, Map<String, String> added) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void untagResource(String region, String arn, List<String> tagKeys) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ResourceTagReader reader(String service, Function<String, Map<String, String>> tags) {
        return new ResourceTagReader() {
            @Override
            public Set<String> services() {
                return Set.of(service);
            }

            @Override
            public Map<String, String> tags(String arn) {
                return tags.apply(arn);
            }
        };
    }
}
