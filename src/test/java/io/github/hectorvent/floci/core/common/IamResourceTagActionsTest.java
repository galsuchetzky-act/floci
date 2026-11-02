package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamResourceTagActionsTest {

    @Test
    void anActionOnATaggableResourceTypeTakesTheKey() {
        assertTrue(IamResourceTagActions.takesResourceTags("sqs:GetQueueAttributes"));
        assertTrue(IamResourceTagActions.takesResourceTags("iam:GetRole"));
        assertTrue(IamResourceTagActions.takesResourceTags("eks:DeleteAccessEntry"));
    }

    @Test
    void actionNamesMatchInAnyCase() {
        assertTrue(IamResourceTagActions.takesResourceTags("SQS:getqueueattributes"));
    }

    @Test
    void anActionOnNoResourceOrAnUnreadNamespaceDoesNotTakeTheKey() {
        assertFalse(IamResourceTagActions.takesResourceTags("sqs:ListQueues"));
        assertFalse(IamResourceTagActions.takesResourceTags("s3:GetObject"));
        assertFalse(IamResourceTagActions.takesResourceTags(null));
    }
}
