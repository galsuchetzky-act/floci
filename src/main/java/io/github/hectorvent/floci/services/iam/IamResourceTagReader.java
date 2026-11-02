package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ResourceTagReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

/** The tags of a role, a user or a customer managed policy, for {@code aws:ResourceTag}. */
@ApplicationScoped
public class IamResourceTagReader implements ResourceTagReader {

    private final IamService iamService;

    @Inject
    public IamResourceTagReader(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> services() {
        return Set.of("iam");
    }

    @Override
    public Map<String, String> tags(String arn) {
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        Map<String, String> tags;
        if (resource.startsWith("role/")) {
            tags = iamService.listRoleTags(name);
        } else if (resource.startsWith("user/")) {
            tags = iamService.listUserTags(name);
        } else if (resource.startsWith("policy/")) {
            tags = iamService.listPolicyTags(arn);
        } else {
            tags = Map.of();
        }
        return tags == null ? Map.of() : tags;
    }
}
