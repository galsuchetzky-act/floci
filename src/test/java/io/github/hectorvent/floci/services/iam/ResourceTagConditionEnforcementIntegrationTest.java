package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * An attribute-based policy allows an action only on resources tagged {@code team=red}
 * ({@code aws:ResourceTag/team}): the tags stored on the resource the request acts on are in the request
 * context, whichever service owns it, so a red resource is allowed and a blue one denied.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ResourceTagConditionEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final String JSON_10 = "application/x-amz-json-1.0";
    private static final String JSON_11 = "application/x-amz-json-1.1";
    private static final String DENIED = "not authorized to perform";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String suffix;
    private String userName;
    private UserCredentials user;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createUser() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        userName = "abac-" + suffix;
        user = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), userName);
    }

    @Test
    void sqsQueueTags() {
        String red = json(ADMIN, "sqs", "AmazonSQS.CreateQueue", JSON_10,
                "{\"QueueName\":\"red-" + suffix + "\",\"tags\":{\"team\":\"red\"}}").jsonPath().getString("QueueUrl");
        String blue = json(ADMIN, "sqs", "AmazonSQS.CreateQueue", JSON_10, "{\"QueueName\":\"blue-" + suffix + "\"}")
                .jsonPath().getString("QueueUrl");
        json(ADMIN, "sqs", "AmazonSQS.TagQueue", JSON_10, "{\"QueueUrl\":\"" + blue + "\",\"Tags\":{\"team\":\"blue\"}}")
                .then().statusCode(200);
        allowOnlyRed("sqs:GetQueueAttributes");

        json(user.accessKeyId(), "sqs", "AmazonSQS.GetQueueAttributes", JSON_10, "{\"QueueUrl\":\"" + red + "\"}")
                .then().statusCode(200);
        json(user.accessKeyId(), "sqs", "AmazonSQS.GetQueueAttributes", JSON_10, "{\"QueueUrl\":\"" + blue + "\"}")
                .then().body(containsString(DENIED));
    }

    @Test
    void anotherAccountsQueueIsJudgedByItsOwnTags() {
        String name = "shared-" + suffix;
        String theirs = json(OTHER_ACCOUNT, "sqs", "AmazonSQS.CreateQueue", JSON_10,
                "{\"QueueName\":\"" + name + "\",\"tags\":{\"team\":\"red\"}}").jsonPath().getString("QueueUrl");
        String ours = json(ADMIN, "sqs", "AmazonSQS.CreateQueue", JSON_10,
                "{\"QueueName\":\"" + name + "\",\"tags\":{\"team\":\"blue\"}}").jsonPath().getString("QueueUrl");
        assertThat(theirs, containsString("/" + OTHER_ACCOUNT + "/"));
        allowOnlyRed("sqs:SendMessage");

        json(user.accessKeyId(), "sqs", "AmazonSQS.SendMessage", JSON_10,
                "{\"QueueUrl\":\"" + theirs + "\",\"MessageBody\":\"x\"}").then().statusCode(200);
        json(user.accessKeyId(), "sqs", "AmazonSQS.SendMessage", JSON_10,
                "{\"QueueUrl\":\"" + ours + "\",\"MessageBody\":\"x\"}").then().body(containsString(DENIED));
    }

    @Test
    void createQueueIsJudgedByTheQueueItNames() {
        String red = json(ADMIN, "sqs", "AmazonSQS.CreateQueue", JSON_10,
                "{\"QueueName\":\"red-" + suffix + "\",\"tags\":{\"team\":\"red\"}}").jsonPath().getString("QueueUrl");
        allowOnlyRed("sqs:CreateQueue");

        json(user.accessKeyId(), "sqs", "AmazonSQS.CreateQueue", JSON_10,
                "{\"QueueName\":\"red-" + suffix + "\"}").then().statusCode(200);
        json(user.accessKeyId(), "sqs", "AmazonSQS.CreateQueue", JSON_10,
                "{\"QueueName\":\"new-" + suffix + "\",\"QueueUrl\":\"" + red + "\"}").then().body(containsString(DENIED));
    }

    @Test
    void snsTopicTags() {
        String red = topic("red");
        String blue = topic("blue");
        allowOnlyRed("sns:GetTopicAttributes");

        query(user.accessKeyId(), "sns", "GetTopicAttributes", "TopicArn", red).then().statusCode(200);
        query(user.accessKeyId(), "sns", "GetTopicAttributes", "TopicArn", blue).then().body(containsString(DENIED));
    }

    @Test
    void snsTaggingActionsReadTheTopicTheyName() {
        String red = topic("red");
        String blue = topic("blue");
        allowOnlyRed("sns:ListTagsForResource");

        query(user.accessKeyId(), "sns", "ListTagsForResource", "ResourceArn", red).then().statusCode(200);
        query(user.accessKeyId(), "sns", "ListTagsForResource", "ResourceArn", blue, "TopicArn", red)
                .then().body(containsString(DENIED));
    }

    @Test
    void aPublishBatchIsJudgedByItsTopicWhateverElseItCarries() {
        String red = topic("red");
        String blue = topic("blue");
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), userName, "Abac",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"sns:Publish\","
                        + "\"Resource\":\"*\"},{\"Effect\":\"Deny\",\"Action\":\"sns:Publish\",\"Resource\":\"*\","
                        + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"red\"}}}]}");

        publishBatch(blue).then().statusCode(200);
        publishBatch(red).then().body(containsString(DENIED));
    }

    @Test
    void iamRoleTags() {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        for (String team : new String[] {"red", "blue"}) {
            String role = team + "-" + suffix;
            query(ADMIN, "iam", "CreateRole", "RoleName", role, "AssumeRolePolicyDocument", trust,
                    "Tags.member.1.Key", "team", "Tags.member.1.Value", team).then().statusCode(200);
            cleanup.register(() -> query(ADMIN, "iam", "DeleteRole", "RoleName", role));
        }
        allowOnlyRed("iam:GetRole");

        query(user.accessKeyId(), "iam", "GetRole", "RoleName", "red-" + suffix).then().statusCode(200);
        query(user.accessKeyId(), "iam", "GetRole", "RoleName", "blue-" + suffix).then().body(containsString(DENIED));
    }

    @Test
    void ssmParameterTags() {
        for (String team : new String[] {"red", "blue"}) {
            json(ADMIN, "ssm", "AmazonSSM.PutParameter", JSON_11, "{\"Name\":\"" + team + "-" + suffix
                    + "\",\"Value\":\"x\",\"Type\":\"String\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"" + team + "\"}]}")
                    .then().statusCode(200);
        }
        allowOnlyRed("ssm:GetParameter");

        json(user.accessKeyId(), "ssm", "AmazonSSM.GetParameter", JSON_11, "{\"Name\":\"red-" + suffix + "\"}")
                .then().statusCode(200);
        json(user.accessKeyId(), "ssm", "AmazonSSM.GetParameter", JSON_11, "{\"Name\":\"blue-" + suffix + "\"}")
                .then().body(containsString(DENIED));
    }

    @Test
    void kmsKeyTags() {
        String red = json(ADMIN, "kms", "TrentService.CreateKey", JSON_11,
                "{\"Tags\":[{\"TagKey\":\"team\",\"TagValue\":\"red\"}]}").jsonPath().getString("KeyMetadata.Arn");
        String blue = json(ADMIN, "kms", "TrentService.CreateKey", JSON_11,
                "{\"Tags\":[{\"TagKey\":\"team\",\"TagValue\":\"blue\"}]}").jsonPath().getString("KeyMetadata.Arn");
        allowOnlyRed("kms:DescribeKey");

        json(user.accessKeyId(), "kms", "TrentService.DescribeKey", JSON_11, "{\"KeyId\":\"" + red + "\"}")
                .then().statusCode(200);
        json(user.accessKeyId(), "kms", "TrentService.DescribeKey", JSON_11, "{\"KeyId\":\"" + blue + "\"}")
                .then().body(containsString(DENIED));
    }

    @Test
    void secretsManagerSecretTags() {
        String red = json(ADMIN, "secretsmanager", "secretsmanager.CreateSecret", JSON_11, "{\"Name\":\"red-" + suffix
                + "\",\"SecretString\":\"x\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"red\"}]}").jsonPath().getString("ARN");
        String blue = json(ADMIN, "secretsmanager", "secretsmanager.CreateSecret", JSON_11, "{\"Name\":\"blue-" + suffix
                + "\",\"SecretString\":\"x\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"blue\"}]}").jsonPath().getString("ARN");
        allowOnlyRed("secretsmanager:DescribeSecret");

        json(user.accessKeyId(), "secretsmanager", "secretsmanager.DescribeSecret", JSON_11,
                "{\"SecretId\":\"" + red + "\"}").then().statusCode(200);
        json(user.accessKeyId(), "secretsmanager", "secretsmanager.DescribeSecret", JSON_11,
                "{\"SecretId\":\"" + blue + "\"}").then().body(containsString(DENIED));
    }

    @Test
    void dynamoDbTableTags() {
        tables();
        allowOnlyRed("dynamodb:DescribeTable");

        json(user.accessKeyId(), "dynamodb", "DynamoDB_20120810.DescribeTable", JSON_10,
                "{\"TableName\":\"red-" + suffix + "\"}").then().statusCode(200);
        json(user.accessKeyId(), "dynamodb", "DynamoDB_20120810.DescribeTable", JSON_10,
                "{\"TableName\":\"blue-" + suffix + "\"}").then().body(containsString(DENIED));
    }

    @Test
    void aDynamoDbBatchJudgesEveryTableByItsOwnTags() {
        tables();
        allowOnlyRed("dynamodb:BatchGetItem");

        json(user.accessKeyId(), "dynamodb", "DynamoDB_20120810.BatchGetItem", JSON_10,
                "{\"RequestItems\":{" + batchKeys("red") + "}}").then().statusCode(200);
        json(user.accessKeyId(), "dynamodb", "DynamoDB_20120810.BatchGetItem", JSON_10,
                "{\"RequestItems\":{" + batchKeys("red") + "," + batchKeys("blue") + "}}")
                .then().body(containsString(DENIED));
    }

    @Test
    void aDynamoDbTransactionJudgesEveryItemByItsTablesTags() {
        tables();
        allowOnlyRed("dynamodb:GetItem");

        json(user.accessKeyId(), "dynamodb", "DynamoDB_20120810.TransactGetItems", JSON_10,
                "{\"TransactItems\":[" + transactGet("red") + "]}").then().statusCode(200);
        json(user.accessKeyId(), "dynamodb", "DynamoDB_20120810.TransactGetItems", JSON_10,
                "{\"TransactItems\":[" + transactGet("red") + "," + transactGet("blue") + "]}")
                .then().body(containsString(DENIED));
    }

    @Test
    void aServiceServedByATagHandlerReadsItsResourcesTags() {
        for (String team : new String[] {"red", "blue"}) {
            String group = team + "-" + suffix;
            given().contentType("application/json").header("Authorization", auth(ADMIN, "scheduler"))
                    .body("{\"Tags\":[{\"Key\":\"team\",\"Value\":\"" + team + "\"}]}")
                    .post("/schedule-groups/" + group).then().statusCode(200);
            cleanup.register(() -> given().header("Authorization", auth(ADMIN, "scheduler"))
                    .delete("/schedule-groups/" + group));
        }
        allowOnlyRed("scheduler:GetScheduleGroup");

        given().header("Authorization", auth(user.accessKeyId(), "scheduler"))
                .get("/schedule-groups/red-" + suffix).then().statusCode(200);
        given().header("Authorization", auth(user.accessKeyId(), "scheduler"))
                .get("/schedule-groups/blue-" + suffix).then().body(containsString(DENIED));
    }

    @Test
    void anActionWithoutAResourceGetsNoResourceTags() {
        json(ADMIN, "sqs", "AmazonSQS.CreateQueue", JSON_10, "{\"QueueName\":\"listed-" + suffix
                + "\",\"tags\":{\"team\":\"red\"}}").then().statusCode(200);
        allowOnlyRed("sqs:ListQueues");

        json(user.accessKeyId(), "sqs", "AmazonSQS.ListQueues", JSON_10, "{}").then().body(containsString(DENIED));
    }

    private void allowOnlyRed(String action) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), userName, "Abac",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"" + action
                        + "\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"red\"}}}]}");
    }

    private String topic(String team) {
        return query(ADMIN, "sns", "CreateTopic", "Name", team + "-" + suffix,
                "Tags.member.1.Key", "team", "Tags.member.1.Value", team)
                .xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    private Response publishBatch(String topicArn) {
        return query(user.accessKeyId(), "sns", "PublishBatch", "TopicArn", topicArn, "PhoneNumber", "+15555550100",
                "PublishBatchRequestEntries.member.1.Id", "one",
                "PublishBatchRequestEntries.member.1.Message", "x");
    }

    private void tables() {
        for (String team : new String[] {"red", "blue"}) {
            json(ADMIN, "dynamodb", "DynamoDB_20120810.CreateTable", JSON_10, "{\"TableName\":\"" + team + "-" + suffix
                    + "\",\"AttributeDefinitions\":[{\"AttributeName\":\"k\",\"AttributeType\":\"S\"}],"
                    + "\"KeySchema\":[{\"AttributeName\":\"k\",\"KeyType\":\"HASH\"}],\"BillingMode\":\"PAY_PER_REQUEST\","
                    + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"" + team + "\"}]}").then().statusCode(200);
        }
    }

    private String batchKeys(String team) {
        return "\"" + team + "-" + suffix + "\":{\"Keys\":[{\"k\":{\"S\":\"a\"}}]}";
    }

    private String transactGet(String team) {
        return "{\"Get\":{\"TableName\":\"" + team + "-" + suffix + "\",\"Key\":{\"k\":{\"S\":\"a\"}}}}";
    }

    private static Response json(String accessKeyId, String service, String target, String contentType, String body) {
        return given().contentType(contentType)
                .header("Authorization", auth(accessKeyId, service))
                .header("X-Amz-Target", target)
                .body(body)
                .post("/");
    }

    private static Response query(String accessKeyId, String service, String action, String... params) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(accessKeyId, service))
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.post("/");
    }
}
