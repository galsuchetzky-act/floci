package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.CfnRequests.cfn;
import static io.floci.aws.testing.LogsRequests.logs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationLogGroupClassIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20261110/us-east-1/cloudformation/aws4_request";

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "INFREQUENT_ACCESS", "DELIVERY"})
    void createsTheDeclaredClass(String logGroupClass) throws JsonProcessingException {
        String stack = "log-class-" + UUID.randomUUID();
        String group = "/cfn/" + stack;
        try {
            create(stack, template(group, logGroupClass, 7, "before"));
            CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", AUTH);
            assertEquals(logGroupClass, logGroup(group).get("logGroupClass"));
        } finally {
            delete(stack);
        }
    }

    @Test
    void omissionUsesStandard() throws JsonProcessingException {
        String stack = "log-class-" + UUID.randomUUID();
        String group = "/cfn/" + stack;
        try {
            create(stack, template(group, null, 7, "before"));
            CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", AUTH);
            assertEquals("STANDARD", logGroup(group).get("logGroupClass"));
        } finally {
            delete(stack);
        }
    }

    @Test
    void resolvesTheClassFromACondition() throws JsonProcessingException {
        String stack = "log-class-" + UUID.randomUUID();
        String group = "/cfn/" + stack;
        try {
            create(stack, template(group,
                    Map.of("Fn::If", List.of("Infrequent", "INFREQUENT_ACCESS", "STANDARD")), 7, "before"));
            CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", AUTH);
            assertEquals("INFREQUENT_ACCESS", logGroup(group).get("logGroupClass"));
        } finally {
            delete(stack);
        }
    }

    @Test
    void unchangedClassAllowsRetentionAndTagUpdates() throws JsonProcessingException {
        String stack = "log-class-" + UUID.randomUUID();
        String group = "/cfn/" + stack;
        try {
            create(stack, template(group, "INFREQUENT_ACCESS", 7, "before"));
            CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", AUTH);
            update(stack, template(group, "INFREQUENT_ACCESS", 14, "after"));
            CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", AUTH);
            assertEquals("INFREQUENT_ACCESS", logGroup(group).get("logGroupClass"));
            assertEquals(14, logGroup(group).get("retentionInDays"));
            assertEquals("after", tags(group).get("phase"));
        } finally {
            delete(stack);
        }
    }

    @Test
    void changedClassFailsBeforeRetentionOrTagsMutate() throws JsonProcessingException {
        String stack = "log-class-" + UUID.randomUUID();
        String group = "/cfn/" + stack;
        try {
            create(stack, template(group, "STANDARD", 7, "before"));
            CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", AUTH);
            update(stack, template(group, "INFREQUENT_ACCESS", 14, "after"));
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
            assertEquals("STANDARD", logGroup(group).get("logGroupClass"));
            assertEquals(7, logGroup(group).get("retentionInDays"));
            assertEquals("before", tags(group).get("phase"));
        } finally {
            delete(stack);
        }
    }

    @Test
    void invalidClassDoesNotCreateALogGroup() throws JsonProcessingException {
        String stack = "log-class-" + UUID.randomUUID();
        String group = "/cfn/" + stack;
        try {
            create(stack, template(group, "ARCHIVE", 7, "before"));
            assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
            List<?> groups = logs("DescribeLogGroups", Map.of("logGroupNamePrefix", group))
                    .then().statusCode(200).extract().jsonPath().getList("logGroups");
            assertTrue(groups.isEmpty());
        } finally {
            delete(stack);
        }
    }

    private static String template(String group, Object logGroupClass, int retention, String tag)
            throws JsonProcessingException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("LogGroupName", group);
        properties.put("RetentionInDays", retention);
        properties.put("Tags", List.of(Map.of("Key", "phase", "Value", tag)));
        if (logGroupClass != null) {
            properties.put("LogGroupClass", logGroupClass);
        }
        return MAPPER.writeValueAsString(Map.of(
                "Conditions", Map.of("Infrequent", Map.of("Fn::Equals", List.of("same", "same"))),
                "Resources", Map.of("Group", Map.of("Type", "AWS::Logs::LogGroup", "Properties", properties))));
    }

    private static void create(String stack, String template) {
        cfn("CreateStack", stack).header("Authorization", AUTH).formParam("TemplateBody", template)
                .post("/").then().statusCode(200);
    }

    private static void update(String stack, String template) {
        cfn("UpdateStack", stack).header("Authorization", AUTH).formParam("TemplateBody", template)
                .post("/").then().statusCode(200);
    }

    private static Map<String, Object> logGroup(String name) {
        return logs("DescribeLogGroups", Map.of("logGroupNamePrefix", name))
                .then().statusCode(200).extract().jsonPath().getMap("logGroups[0]");
    }

    private static Map<String, String> tags(String name) {
        return logs("ListTagsLogGroup", Map.of("logGroupName", name))
                .then().statusCode(200).extract().jsonPath().getMap("tags");
    }

    private static void delete(String stack) {
        cfn("DeleteStack", stack).header("Authorization", AUTH).post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack, AUTH);
    }
}
