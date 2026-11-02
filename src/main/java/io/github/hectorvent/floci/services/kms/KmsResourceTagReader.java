package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ResourceTagReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

/** A key's tags, for {@code aws:ResourceTag}. An alias ARN has none: the key condition belongs to the key. */
@ApplicationScoped
public class KmsResourceTagReader implements ResourceTagReader {

    private final KmsService kmsService;

    @Inject
    public KmsResourceTagReader(KmsService kmsService) {
        this.kmsService = kmsService;
    }

    @Override
    public Set<String> services() {
        return Set.of("kms");
    }

    @Override
    public Map<String, String> tags(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.resource().startsWith("key/")) {
            return Map.of();
        }
        Map<String, String> tags = kmsService.describeKey(arn, parsed.region()).getTags();
        return tags == null ? Map.of() : tags;
    }
}
