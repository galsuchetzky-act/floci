package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("aws:ResourceTag conditions on SQS, SNS, KMS and Secrets Manager with IAM enforcement")
class ResourceTagAuthorizationTest {

    private static final String POLICY = "abac";

    private final List<Executable> cleanups = new ArrayList<>();
    private IamClient iam;
    private SqsClient sqs;
    private SnsClient sns;
    private KmsClient kms;
    private SecretsManagerClient secrets;
    private StaticCredentialsProvider caller;
    private String user;
    private String name;

    @BeforeEach
    void setUp() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses emulator account-root credentials");
        iam = TestFixtures.iamClient();
        sqs = TestFixtures.sqsClient();
        sns = TestFixtures.snsClient();
        kms = TestFixtures.kmsClient();
        secrets = TestFixtures.secretsManagerClient();
        name = TestFixtures.uniqueName("resource-tag");
        iam.createUser(r -> r.userName(name));
        user = name;
        cleanups.add(() -> iam.deleteUser(r -> r.userName(user)));
        AccessKey key = iam.createAccessKey(r -> r.userName(user)).accessKey();
        cleanups.add(0, () -> iam.deleteAccessKey(r -> r.userName(user).accessKeyId(key.accessKeyId())));
        caller = StaticCredentialsProvider.create(AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()));
        boolean enforced = false;
        try (SqsClient client = sqs(caller)) {
            client.listQueues();
        } catch (AwsServiceException error) {
            assertDenied(error);
            enforced = true;
        }
        Assumptions.assumeTrue(enforced, "Requires floci.services.iam.enforcement-enabled=true");
    }

    @AfterEach
    void cleanUp() {
        List<Executable> tasks = new ArrayList<>(cleanups);
        tasks.add(() -> {
            for (AutoCloseable client : new AutoCloseable[] {sqs, sns, kms, secrets, iam}) {
                if (client != null) {
                    client.close();
                }
            }
        });
        assertAll("remove the tagged resources and the IAM user", tasks.stream());
    }

    @Test
    void aQueueIsReadOnlyWhenItCarriesTheTag() {
        String red = queue("red");
        String blue = queue("blue");
        allowOnlyRed("sqs:GetQueueAttributes");

        try (SqsClient client = sqs(caller)) {
            assertThat(client.getQueueAttributes(r -> r.queueUrl(red).attributeNames(QueueAttributeName.QUEUE_ARN))
                    .attributes()).containsKey(QueueAttributeName.QUEUE_ARN);
            assertThatThrownBy(() -> client.getQueueAttributes(r -> r.queueUrl(blue)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)))
                    .isInstanceOfSatisfying(AwsServiceException.class, ResourceTagAuthorizationTest::assertDenied);
        }
    }

    @Test
    void aTopicIsReadOnlyWhenItCarriesTheTag() {
        String red = topic("red");
        String blue = topic("blue");
        allowOnlyRed("sns:GetTopicAttributes");

        try (SnsClient client = SnsClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region()).credentialsProvider(caller).build()) {
            assertThat(client.getTopicAttributes(r -> r.topicArn(red)).attributes()).containsEntry("TopicArn", red);
            assertThatThrownBy(() -> client.getTopicAttributes(r -> r.topicArn(blue)))
                    .isInstanceOfSatisfying(AwsServiceException.class, ResourceTagAuthorizationTest::assertDenied);
        }
    }

    @Test
    void aKeyIsDescribedOnlyWhenItCarriesTheTag() {
        String red = key("red");
        String blue = key("blue");
        allowOnlyRed("kms:DescribeKey");

        try (KmsClient client = KmsClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region()).credentialsProvider(caller).build()) {
            assertThat(client.describeKey(r -> r.keyId(red)).keyMetadata().keyId()).isEqualTo(red);
            assertThatThrownBy(() -> client.describeKey(r -> r.keyId(blue)))
                    .isInstanceOfSatisfying(AwsServiceException.class, ResourceTagAuthorizationTest::assertDenied);
        }
    }

    @Test
    void aSecretIsDescribedOnlyWhenItCarriesTheTag() {
        String red = secret("red");
        String blue = secret("blue");
        allowOnlyRed("secretsmanager:DescribeSecret");

        try (SecretsManagerClient client = SecretsManagerClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region()).credentialsProvider(caller).build()) {
            assertThat(client.describeSecret(r -> r.secretId(red)).name()).isEqualTo(red);
            assertThatThrownBy(() -> client.describeSecret(r -> r.secretId(blue)))
                    .isInstanceOfSatisfying(AwsServiceException.class, ResourceTagAuthorizationTest::assertDenied);
        }
    }

    private String queue(String team) {
        String url = sqs.createQueue(r -> r.queueName(name + "-" + team).tags(Map.of("team", team))).queueUrl();
        cleanups.add(0, () -> sqs.deleteQueue(r -> r.queueUrl(url)));
        return url;
    }

    private String topic(String team) {
        String arn = sns.createTopic(r -> r.name(name + "-" + team).tags(t -> t.key("team").value(team))).topicArn();
        cleanups.add(0, () -> sns.deleteTopic(r -> r.topicArn(arn)));
        return arn;
    }

    private String key(String team) {
        String keyId = kms.createKey(r -> r.tags(t -> t.tagKey("team").tagValue(team))).keyMetadata().keyId();
        cleanups.add(0, () -> kms.scheduleKeyDeletion(r -> r.keyId(keyId).pendingWindowInDays(7)));
        return keyId;
    }

    private String secret(String team) {
        String secretName = name + "-" + team;
        secrets.createSecret(r -> r.name(secretName).secretString("x").tags(t -> t.key("team").value(team)));
        cleanups.add(0, () -> secrets.deleteSecret(r -> r.secretId(secretName).forceDeleteWithoutRecovery(true)));
        return secretName;
    }

    private void allowOnlyRed(String action) {
        iam.putUserPolicy(r -> r.userName(user).policyName(POLICY).policyDocument(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"" + action
                        + "\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"red\"}}}]}"));
        cleanups.add(0, () -> iam.deleteUserPolicy(r -> r.userName(user).policyName(POLICY)));
    }

    private static SqsClient sqs(StaticCredentialsProvider credentials) {
        return SqsClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(credentials).build();
    }

    private static void assertDenied(AwsServiceException error) {
        assertThat(error.awsErrorDetails().errorCode()).contains("AccessDenied");
    }
}
