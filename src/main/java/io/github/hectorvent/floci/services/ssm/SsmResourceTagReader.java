package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ResourceTagReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

/** A parameter's tags, for {@code aws:ResourceTag}. */
@ApplicationScoped
public class SsmResourceTagReader implements ResourceTagReader {

    private final SsmService ssmService;

    @Inject
    public SsmResourceTagReader(SsmService ssmService) {
        this.ssmService = ssmService;
    }

    @Override
    public Set<String> services() {
        return Set.of("ssm");
    }

    @Override
    public Map<String, String> tags(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.resource().startsWith("parameter/")) {
            return Map.of();
        }
        return ssmService.listTagsForResource(arn, parsed.region());
    }
}
