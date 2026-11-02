package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ResourceTagReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

/** A table's tags, for {@code aws:ResourceTag}, also given for one of its indexes; a stream or export has none. */
@ApplicationScoped
public class DynamoDbResourceTagReader implements ResourceTagReader {

    private final DynamoDbFacade dynamoDb;

    @Inject
    public DynamoDbResourceTagReader(DynamoDbFacade dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    @Override
    public Set<String> services() {
        return Set.of("dynamodb");
    }

    @Override
    public Map<String, String> tags(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String[] segments = parsed.resource().split("/");
        boolean tableOrIndex = segments.length == 2 || segments.length == 4 && "index".equals(segments[2]);
        if (!"table".equals(segments[0]) || !tableOrIndex) {
            return Map.of();
        }
        return dynamoDb.tables().tableTags(dynamoDb.scope(parsed.region()), segments[1]);
    }
}
