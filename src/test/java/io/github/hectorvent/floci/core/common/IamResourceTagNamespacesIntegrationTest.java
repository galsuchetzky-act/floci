package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The aws:ResourceTag action table lists the namespaces whose resources {@link ResourceTags} can read the tags of:
 * those of every registered {@link ResourceTagReader} and {@link TagHandler}. A service that gains either is added to
 * {@code NAMESPACES} in {@code tools/aws/regen_iam_resource_tag_actions.py}, and the table regenerated.
 */
@QuarkusTest
class IamResourceTagNamespacesIntegrationTest {

    @Inject
    @Any
    Instance<TagHandler> tagHandlers;

    @Inject
    @Any
    Instance<ResourceTagReader> readers;

    @Test
    void theTableListsEveryServiceWhoseTagsAreRead() {
        Set<String> served = new TreeSet<>();
        tagHandlers.forEach(handler -> served.add(handler.serviceKey()));
        readers.forEach(reader -> served.addAll(reader.services()));
        Set<String> listed = IamResourceTagActions.actions().stream()
                .map(action -> action.substring(0, action.indexOf(':')))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(served, listed);
    }
}
