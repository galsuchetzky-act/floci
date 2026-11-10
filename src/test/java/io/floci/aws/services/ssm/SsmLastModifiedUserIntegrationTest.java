package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterAlreadyExistsException;
import software.amazon.awssdk.services.ssm.model.ParameterHistory;
import software.amazon.awssdk.services.ssm.model.ParameterMetadata;
import software.amazon.awssdk.services.ssm.model.ParameterStringFilter;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class SsmLastModifiedUserIntegrationTest {
    @Inject
    SsmService service;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,111111111111,aws", "eu-west-2,222222222222,aws",
            "cn-north-1,333333333333,aws-cn", "us-gov-west-1,444444444444,aws-us-gov"})
    void metadataAndHistoryReportTheWritingAccount(String region, String account, String partition) {
        String name = "/modified-user-" + UUID.randomUUID();
        try (SsmClient client = AwsTestClients.create(SsmClient.builder(), account, Region.of(region))) {
            try {
                client.putParameter(request -> request.name(name).value("first").type("String"));
                String arn = "arn:" + partition + ":iam::" + account + ":root";
                assertEquals(arn, metadata(client, name).lastModifiedUser());
                assertEquals(arn, client.getParameterHistory(request -> request.name(name))
                        .parameters().getFirst().lastModifiedUser());
            } finally {
                client.deleteParameter(request -> request.name(name));
            }
        }
    }

    @Test
    void overwriteKeepsEachVersionsWriterAndRejectedWritesKeepTheCurrentWriter() {
        String account = "000000000000";
        String firstName = "ssm-writer-one-" + UUID.randomUUID();
        String secondName = "ssm-writer-two-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials first = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(account, "iam"), firstName);
        EnforcementFixtures.UserCredentials second = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(account, "iam"), secondName);
        String name = "/modified-writers-" + UUID.randomUUID();
        try (SsmClient one = AwsTestClients.create(SsmClient.builder(), first.accessKeyId(),
                first.secretAccessKey(), Region.US_EAST_1);
             SsmClient two = AwsTestClients.create(SsmClient.builder(), second.accessKeyId(),
                     second.secretAccessKey(), Region.US_EAST_1)) {
            try {
                one.putParameter(request -> request.name(name).value("first").type("String"));
                two.putParameter(request -> request.name(name).value("second").type("String").overwrite(true));
                assertThrows(ParameterAlreadyExistsException.class,
                        () -> one.putParameter(request -> request.name(name).value("rejected").type("String")));
                List<ParameterHistory> history = two.getParameterHistory(request -> request.name(name)).parameters();
                assertEquals(2, history.size());
                assertEquals("arn:aws:iam::" + account + ":user/" + firstName, history.get(0).lastModifiedUser());
                assertEquals("arn:aws:iam::" + account + ":user/" + secondName, history.get(1).lastModifiedUser());
                assertEquals(history.get(1).lastModifiedUser(), metadata(two, name).lastModifiedUser());
            } finally {
                two.deleteParameter(request -> request.name(name));
            }
        }
    }

    @Test
    void recordsWithoutAStoredCallerDoNotGuessTheReader() {
        String name = "/legacy-writer-" + UUID.randomUUID();
        service.putParameter(name, "old", "String", null, false, "us-east-1");
        try (SsmClient client = AwsTestClients.create(SsmClient.builder(), "000000000000", Region.US_EAST_1)) {
            try {
                assertNull(metadata(client, name).lastModifiedUser());
                assertNull(client.getParameterHistory(request -> request.name(name))
                        .parameters().getFirst().lastModifiedUser());
            } finally {
                client.deleteParameter(request -> request.name(name));
            }
        }
    }

    @Test
    void roleSessionRecordsItsCallerArn() {
        String account = "000000000000";
        String roleName = "ssm-writer-role-" + UUID.randomUUID();
        String roleArn = EnforcementFixtures.createRole(cleanup, RestAssured.port, account, "/", roleName,
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"ssm:*\",\"Resource\":\"*\"}]}");
        EnforcementFixtures.Session session = EnforcementFixtures.assumeRole(RestAssured.port, account, roleArn);
        String name = "/session-writer-" + UUID.randomUUID();
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), account, Region.US_EAST_1)) {
            try {
                given().header("Authorization", auth(session.accessKeyId(), "ssm"))
                        .header("X-Amz-Security-Token", session.sessionToken())
                        .header("X-Amz-Target", "AmazonSSM.PutParameter")
                        .contentType("application/x-amz-json-1.1")
                        .body("{\"Name\":\"" + name + "\",\"Value\":\"session\",\"Type\":\"String\"}")
                        .post("/").then().statusCode(200);
                String caller = "arn:aws:sts::" + account + ":assumed-role/" + roleName
                        + "/" + EnforcementFixtures.SESSION_NAME;
                assertEquals(caller, metadata(root, name).lastModifiedUser());
                assertEquals(caller, root.getParameterHistory(request -> request.name(name))
                        .parameters().getFirst().lastModifiedUser());
            } finally {
                root.deleteParameter(request -> request.name(name));
            }
        }
    }

    private static ParameterMetadata metadata(SsmClient client, String name) {
        ParameterStringFilter filter = ParameterStringFilter.builder().key("Name").option("Equals").values(name).build();
        return client.describeParameters(request -> request.parameterFilters(filter)).parameters().getFirst();
    }
}
