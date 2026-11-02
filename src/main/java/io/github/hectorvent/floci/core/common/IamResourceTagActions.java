package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The actions IAM evaluates with {@code aws:ResourceTag/<key>}, read once from the vendored
 * {@code aws/iam-resource-tag-actions.json}. {@code tools/aws/regen_iam_resource_tag_actions.py} generates
 * it from AWS's Service Reference: an action is in it when it, or a resource type it acts on, lists the key.
 * Only the namespaces whose resources {@link ResourceTags} can read the tags of are listed.
 *
 * <p>A missing or malformed resource is a build defect, so loading fails with
 * {@link IllegalStateException}.
 */
final class IamResourceTagActions {

    static final String RESOURCE_NAME = "aws/iam-resource-tag-actions.json";

    private static final class Holder {
        static final Set<String> ACTIONS = VendoredResources.read(RESOURCE_NAME, "IAM resource-tag action table",
                in -> parse(new ObjectMapper().readTree(in)));
    }

    private IamResourceTagActions() {
    }

    /** Whether IAM evaluates {@code action}, as {@code <namespace>:<Action>} in any case, with the resource's tags. */
    static boolean takesResourceTags(String action) {
        return action != null && Holder.ACTIONS.contains(action.toLowerCase(Locale.ROOT));
    }

    /** Every action the table lists, as {@code <namespace>:<action>} in lower case. */
    static Set<String> actions() {
        return Holder.ACTIONS;
    }

    static Set<String> parse(JsonNode document) {
        JsonNode namespaces = document.path("actions");
        if (!namespaces.isObject() || namespaces.isEmpty()) {
            throw new IllegalStateException(RESOURCE_NAME + " carries no actions");
        }
        Set<String> actions = new HashSet<>();
        namespaces.properties().forEach(namespace -> namespace.getValue()
                .forEach(action -> actions.add(action.asText().toLowerCase(Locale.ROOT))));
        return Set.copyOf(actions);
    }
}
