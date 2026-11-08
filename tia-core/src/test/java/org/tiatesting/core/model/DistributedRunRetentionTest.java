package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DistributedRunRetention#decide}: which existing distributed runs a new plan
 * deletes, and which of those it warns about.
 */
class DistributedRunRetentionTest {

    private static final long NOW = 100L * 24L * 60L * 60L * 1000L;

    /**
     * Build an unsealed run.
     *
     * @param runId the run id
     * @param updatesMapping whether the run owns mapping updates
     * @param createdAtMs when it was planned
     * @return the run
     */
    private static DistributedRun open(String runId, boolean updatesMapping, long createdAtMs) {
        return new DistributedRun(runId, "main", "c1", DistributedRunStatus.OPEN, 1, 1, null, 0L,
                createdAtMs, null, null, SelectionMode.SELECTIVE, null, updatesMapping);
    }

    /**
     * Build a sealed run.
     *
     * @param runId the run id
     * @param updatesMapping whether the run owns mapping updates
     * @param createdAtMs when it was planned
     * @param sealedAtMs when it was sealed, or null for a malformed row
     * @return the run
     */
    private static DistributedRun sealed(String runId, boolean updatesMapping, long createdAtMs, Long sealedAtMs) {
        return new DistributedRun(runId, "main", "c1", DistributedRunStatus.SEALED, 1, 1, null, 0L,
                createdAtMs, "runner-1", sealedAtMs, SelectionMode.SELECTIVE, null, updatesMapping);
    }

    /**
     * Verify a preflight plan leaves another build's in-flight preflight run alone - the collision
     * this rule exists to stop.
     */
    @Test
    void preflightPlanKeepsAConcurrentPreflightRun() {
        // given
        List<DistributedRun> existing = Collections.singletonList(open("pre-a", false, NOW - 1000L));

        // when
        DistributedRunRetention retention = DistributedRunRetention.decide(existing, "pre-b", false, NOW);

        // then
        assertTrue(retention.getRunIdsToDelete().isEmpty());
        assertTrue(retention.getSupersededUnsealed().isEmpty());
        assertTrue(retention.getAbandoned().isEmpty());
    }

    /**
     * Verify a preflight plan never deletes a mapping-owning run, however old or unsealed it is.
     */
    @Test
    void preflightPlanNeverDeletesAPrimaryRun() {
        // given - even an ancient unsealed primary run
        List<DistributedRun> existing = Collections.singletonList(open("primary-1", true, 0L));

        // when
        DistributedRunRetention retention = DistributedRunRetention.decide(existing, "pre-b", false, NOW);

        // then
        assertTrue(retention.getRunIdsToDelete().isEmpty());
    }

    /**
     * Verify a primary plan deletes every earlier primary run, sealed or not, but names only the
     * unsealed one for a warning.
     */
    @Test
    void primaryPlanSupersedesEveryPreviousPrimaryRunAndWarnsOnlyAboutUnsealedOnes() {
        // given
        DistributedRun unsealed = open("primary-2", true, NOW - 1000L);
        List<DistributedRun> existing = Arrays.asList(unsealed, sealed("primary-1", true, NOW - 5000L, NOW - 4000L));

        // when
        DistributedRunRetention retention = DistributedRunRetention.decide(existing, "primary-3", true, NOW);

        // then
        assertEquals(Arrays.asList("primary-1", "primary-2"), retention.getRunIdsToDelete());
        assertEquals(Collections.singletonList(unsealed), retention.getSupersededUnsealed());
        assertTrue(retention.getAbandoned().isEmpty());
    }

    /**
     * Verify a primary plan does not supersede a preflight run that is still inside its retention
     * window.
     */
    @Test
    void primaryPlanKeepsAYoungPreflightRun() {
        // given
        List<DistributedRun> existing = Collections.singletonList(open("pre-a", false, NOW - 1000L));

        // when
        DistributedRunRetention retention = DistributedRunRetention.decide(existing, "primary-1", true, NOW);

        // then
        assertTrue(retention.getRunIdsToDelete().isEmpty());
    }

    /**
     * Verify a plan under an existing run id always replaces that run, warning about it when it
     * never sealed.
     */
    @Test
    void replanOfTheSameIdIsDeletedAndWarnedAboutWhenUnsealed() {
        // given - a preflight run replanned under its own id
        DistributedRun previous = open("pre-a", false, NOW - 1000L);

        // when
        DistributedRunRetention retention = DistributedRunRetention.decide(
                Collections.singletonList(previous), "pre-a", false, NOW);

        // then
        assertEquals(Collections.singletonList("pre-a"), retention.getRunIdsToDelete());
        assertEquals(Collections.singletonList(previous), retention.getSupersededUnsealed());
    }

    /**
     * Verify the sealed grace boundary: a sealed preflight run is kept at exactly the grace and
     * deleted one millisecond past it, with no warning.
     */
    @Test
    void sealedPreflightRunIsKeptInsideTheGraceAndDeletedAfterIt() {
        // given
        DistributedRun inside = sealed("pre-inside", false, NOW - 10_000L,
                NOW - DistributedRunRetention.SEALED_GRACE_MS);
        DistributedRun past = sealed("pre-past", false, NOW - 10_000L,
                NOW - DistributedRunRetention.SEALED_GRACE_MS - 1L);

        // when
        DistributedRunRetention retention = DistributedRunRetention.decide(
                Arrays.asList(inside, past), "pre-new", false, NOW);

        // then - deleted silently: a sealed run left no abandoned work
        assertEquals(Collections.singletonList("pre-past"), retention.getRunIdsToDelete());
        assertTrue(retention.getSupersededUnsealed().isEmpty());
        assertTrue(retention.getAbandoned().isEmpty());
    }

    /**
     * Verify the abandoned boundary: an unsealed preflight run is kept at exactly the age limit and
     * reaped as abandoned one millisecond past it.
     */
    @Test
    void unsealedPreflightRunIsReapedAsAbandonedOnlyPastTheAgeLimit() {
        // given
        DistributedRun young = open("pre-young", false, NOW - DistributedRunRetention.ABANDONED_AGE_MS);
        DistributedRun old = open("pre-old", false, NOW - DistributedRunRetention.ABANDONED_AGE_MS - 1L);

        // when - a primary plan does the reaping just as well as a preflight one
        DistributedRunRetention retention = DistributedRunRetention.decide(
                Arrays.asList(young, old), "primary-1", true, NOW);

        // then
        assertEquals(Collections.singletonList("pre-old"), retention.getRunIdsToDelete());
        assertEquals(Collections.singletonList(old), retention.getAbandoned());
        assertTrue(retention.getSupersededUnsealed().isEmpty());
    }

    /**
     * Verify a sealed run with no seal time recorded is aged from its creation time instead, and
     * deleted without a warning.
     */
    @Test
    void sealedPreflightRunWithNoSealTimeFallsBackToTheAgeLimit() {
        // given
        DistributedRun youngMalformed = sealed("pre-young", false, NOW - 1000L, null);
        DistributedRun oldMalformed = sealed("pre-old", false,
                NOW - DistributedRunRetention.ABANDONED_AGE_MS - 1L, null);

        // when
        DistributedRunRetention retention = DistributedRunRetention.decide(
                Arrays.asList(youngMalformed, oldMalformed), "pre-new", false, NOW);

        // then - deleted, but not warned about, since it is sealed
        assertEquals(Collections.singletonList("pre-old"), retention.getRunIdsToDelete());
        assertTrue(retention.getAbandoned().isEmpty());
    }
}
