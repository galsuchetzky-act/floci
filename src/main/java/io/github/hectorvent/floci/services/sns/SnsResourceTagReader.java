package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ResourceTagReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

/** A topic's tags, for {@code aws:ResourceTag}. */
@ApplicationScoped
public class SnsResourceTagReader implements ResourceTagReader {

    private final SnsService snsService;

    @Inject
    public SnsResourceTagReader(SnsService snsService) {
        this.snsService = snsService;
    }

    @Override
    public Set<String> services() {
        return Set.of("sns");
    }

    @Override
    public Map<String, String> tags(String arn) {
        return snsService.listTagsForResource(arn, AwsArnUtils.parse(arn).region());
    }
}
