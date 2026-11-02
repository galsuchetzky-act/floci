package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ResourceTagReader;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A secret's tags, for {@code aws:ResourceTag}. */
@ApplicationScoped
public class SecretsManagerResourceTagReader implements ResourceTagReader {

    private final SecretsManagerService secretsManagerService;

    @Inject
    public SecretsManagerResourceTagReader(SecretsManagerService secretsManagerService) {
        this.secretsManagerService = secretsManagerService;
    }

    @Override
    public Set<String> services() {
        return Set.of("secretsmanager");
    }

    @Override
    public Map<String, String> tags(String arn) {
        List<Secret.Tag> tags = secretsManagerService.describeSecret(arn, AwsArnUtils.parse(arn).region()).getTags();
        Map<String, String> byKey = new LinkedHashMap<>();
        if (tags != null) {
            tags.forEach(tag -> byKey.put(tag.key(), tag.value()));
        }
        return byKey;
    }
}
