package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the two shapes a {@link DistributedRunnerContext} can take - a runner holding a group and a
 * surplus runner holding none - and the validation that keeps a third, unusable shape from
 * existing. The identity values matter because the completion write is guarded on all three at
 * once: a context carrying a blank run id or runner key would match no row, so the group would
 * stay open and the build would never seal.
 */
class DistributedRunnerContextTest {

    /**
     * A claimed runner's context reports itself claimed and carries the run, identity and group it
     * was built from, with surrounding whitespace trimmed off the two identity values so a run id
     * passed through a CI variable with a trailing newline still matches the stored rows.
     */
    @Test
    void claimedContextCarriesTheRunIdentityAndGroupTrimmed() {
        // given / when
        DistributedRunnerContext context =
                DistributedRunnerContext.forClaimedGroup(" run-1 ", " runner-a ", 3);

        // then
        assertTrue(context.isClaimed());
        assertEquals("run-1", context.getRunId());
        assertEquals("runner-a", context.getRunnerKey());
        assertEquals(Integer.valueOf(3), context.getGroupNumber());
    }

    /**
     * A surplus runner - one a pipeline fanned out wider than the plan's group count - is a
     * legitimate state rather than an error, so it gets a context with an identity but no group.
     */
    @Test
    void surplusContextIsNotClaimedAndHasNoGroup() {
        // given / when
        DistributedRunnerContext context = DistributedRunnerContext.surplusRunner("run-1", "runner-a");

        // then
        assertFalse(context.isClaimed());
        assertNull(context.getGroupNumber());
        assertEquals("run-1", context.getRunId());
        assertEquals("runner-a", context.getRunnerKey());
    }

    /**
     * A blank run id is rejected at construction rather than allowed to reach the datastore, where
     * it would key every write to a run that does not exist.
     */
    @Test
    void blankRunIdIsRejected() {
        // given
        String blankRunId = "  ";

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> DistributedRunnerContext.forClaimedGroup(blankRunId, "runner-a", 0));
        assertThrows(IllegalArgumentException.class,
                () -> DistributedRunnerContext.surplusRunner(blankRunId, "runner-a"));
    }

    /**
     * A missing runner key is rejected for the same reason: the completion write is guarded on the
     * key that claimed the group, so a null one would silently never complete the group.
     */
    @Test
    void missingRunnerKeyIsRejected() {
        // given
        String noRunnerKey = null;

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> DistributedRunnerContext.forClaimedGroup("run-1", noRunnerKey, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DistributedRunnerContext.surplusRunner("run-1", noRunnerKey));
    }

    /**
     * A negative group number is rejected rather than treated as "no group": groups are zero-based
     * indexes into the plan, so a negative one is a caller bug, and quietly reading it as a surplus
     * runner would drop that group's results without anything failing.
     */
    @Test
    void negativeGroupNumberIsRejected() {
        // given
        int negativeGroupNumber = -1;

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> DistributedRunnerContext.forClaimedGroup("run-1", "runner-a", negativeGroupNumber));
    }
}
