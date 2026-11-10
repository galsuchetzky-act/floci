package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;

import java.util.Map;

final class BedrockAgentCoreTagValidation {

    private BedrockAgentCoreTagValidation() {
    }

    static void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isObject() || tags.size() > 50) {
            throw new AwsException("ValidationException", "tags must be an object with at most 50 entries", 400);
        }
        tags.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode rawValue = entry.getValue();
            validateKey(key);
            if (!rawValue.isTextual()) {
                throw new AwsException("ValidationException", "tag value must be a string", 400);
            }
            validateValue(rawValue.asText());
        });
    }

    static void validateTagMap(Map<String, String> tags) {
        if (tags == null || tags.size() > 50) {
            throw new AwsException("ValidationException", "tags must be an object with at most 50 entries", 400);
        }
        tags.forEach((key, value) -> {
            validateKey(key);
            validateValue(value);
        });
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 1 || key.length() > 128 || !key.matches("[a-zA-Z0-9\\s._:/=+@-]*")) {
            throw new AwsException("ValidationException", "tag key does not satisfy AgentCore constraints", 400);
        }
    }

    private static void validateValue(String value) {
        if (value == null) {
            throw new AwsException("ValidationException", "tag value must be a string", 400);
        }
        if (value.length() > 256 || !value.matches("[a-zA-Z0-9\\s._:/=+@-]*")) {
            throw new AwsException("ValidationException", "tag value does not satisfy AgentCore constraints", 400);
        }
    }
}
