package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code aws:ResourceTag/<key>}: the tags already on the resource a request acts on, for an action
 * {@link IamResourceTagActions} lists. They are read from the service that owns the resource, chosen by the
 * {@code service} segment of its ARN, through its {@link ResourceTagReader} or else its {@link TagHandler}, in the
 * resource's account and Region. A resource that does not exist, or that no reader serves, has none, and its keys
 * stay out of the context: a condition testing one then fails, as on AWS. A lookup that fails for any other reason
 * fails the request.
 */
@ApplicationScoped
public class ResourceTags {

    private static final Logger LOG = Logger.getLogger(ResourceTags.class);
    static final String RESOURCE_TAG_PREFIX = "aws:ResourceTag/";

    private final Iterable<TagHandler> tagHandlers;
    private final Iterable<ResourceTagReader> readers;

    @Inject
    public ResourceTags(@Any Instance<TagHandler> tagHandlers, @Any Instance<ResourceTagReader> readers) {
        this((Iterable<TagHandler>) tagHandlers, (Iterable<ResourceTagReader>) readers);
    }

    ResourceTags(Iterable<TagHandler> tagHandlers, Iterable<ResourceTagReader> readers) {
        this.tagHandlers = tagHandlers;
        this.readers = readers;
    }

    /**
     * The {@code aws:ResourceTag/<key>} entries for {@code action} on {@code resourceArn}, read in {@code accountId},
     * or in the ARN's account when that is null: empty when the action does not take the key, the ARN names no single
     * resource, or the resource has no tags.
     */
    public Map<String, List<String>> conditionKeys(String action, String resourceArn, String accountId,
                                                   String requestRegion) {
        if (!IamResourceTagActions.takesResourceTags(action) || !AwsArnUtils.isArn(resourceArn)
                || resourceArn.contains("*") || resourceArn.contains("?")) {
            return Map.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String region = arn.region() == null || arn.region().isBlank() ? requestRegion : arn.region();
        String account = accountId != null ? accountId : arn.accountId();
        Map<String, String> tags;
        try {
            tags = account == null || account.isBlank()
                    ? read(arn.service(), region, resourceArn)
                    : RequestScopes.callAs(account, region, () -> read(arn.service(), region, resourceArn));
        } catch (AwsException e) {
            if (e.getHttpStatus() >= 500) {
                throw e;
            }
            LOG.debugv("No tags for {0}: {1}", resourceArn, e.getMessage());
            return Map.of();
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        tags.forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value == null ? "" : value)));
        return conditions;
    }

    private Map<String, String> read(String service, String region, String arn) {
        for (ResourceTagReader reader : readers) {
            if (reader.services().contains(service)) {
                return reader.tags(arn);
            }
        }
        for (TagHandler handler : tagHandlers) {
            if (handler.serviceKey().equals(service)) {
                Map<String, String> tags = handler.listTags(region, arn);
                return tags == null ? Map.of() : tags;
            }
        }
        return Map.of();
    }

    /** Whether {@code context} already carries a resource tag key, which its service's own resolver put there. */
    static boolean hasResourceTags(Map<String, List<String>> context) {
        return context != null && context.keySet().stream().anyMatch(key -> key.startsWith(RESOURCE_TAG_PREFIX));
    }
}
