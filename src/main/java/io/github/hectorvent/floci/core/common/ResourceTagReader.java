package io.github.hectorvent.floci.core.common;

import java.util.Map;
import java.util.Set;

/**
 * Reads the tags on a resource by its ARN, for a service whose tags are not served through a
 * {@link TagHandler}. {@link ResourceTags} calls it in the account and Region the ARN names.
 */
public interface ResourceTagReader {

    /** The ARN {@code service} segments this reader answers for. */
    Set<String> services();

    /**
     * The tags on the resource {@code arn} names, empty when it has none. A resource this reader does not
     * hold raises the service's own not-found {@link AwsException}.
     */
    Map<String, String> tags(String arn);
}
