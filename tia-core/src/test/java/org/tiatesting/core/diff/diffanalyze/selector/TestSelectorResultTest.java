package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that {@link TestSelectorResult}'s constructor wires every field through to its
 * matching getter, including the runtime-estimate fields added for the select-tests
 * "estimated total run time" feature.
 */
class TestSelectorResultTest {

    /**
     * Constructing a {@link TestSelectorResult} with explicit values for every field
     * should yield identical values from each getter.
     */
    @Test
    void constructor_setsEstimateFields(){
        // given
        Set<String> testsToRun = new HashSet<>(Arrays.asList("tracked1", "tracked2", "newTest1", "newTest2"));
        Set<String> testsToIgnore = new HashSet<>(Collections.singletonList("ignored1"));
        Set<String> withoutStats = new LinkedHashSet<>(Arrays.asList("newTest1", "newTest2"));
        Map<String, Long> perTestRunTimes = new HashMap<>();
        perTestRunTimes.put("tracked1", 100L);
        perTestRunTimes.put("tracked2", 200L);
        perTestRunTimes.put("newTest1", 250L);
        perTestRunTimes.put("newTest2", 250L);
        long estimatedRunTimeMs = 800L;
        long medianRunTimeMsAppliedToMissing = 250L;

        // when
        TestSelectorResult result = new TestSelectorResult(testsToRun, testsToIgnore, null,
                estimatedRunTimeMs, withoutStats, medianRunTimeMsAppliedToMissing, perTestRunTimes, 0L, 0L, false);

        // then
        assertSame(testsToRun, result.getTestsToRun());
        assertSame(testsToIgnore, result.getTestsToIgnore());
        assertEquals(estimatedRunTimeMs, result.getEstimatedRunTimeMs());
        assertSame(withoutStats, result.getSelectedTestsWithoutStats());
        assertEquals(medianRunTimeMsAppliedToMissing, result.getMedianRunTimeMsAppliedToMissing());
        assertSame(perTestRunTimes, result.getSelectedTestRunTimesMs());
        assertEquals(false, result.isRunAllTests());
    }

    /**
     * Verifies that {@code runAllTests} round-trips through the constructor to its getter, since
     * this is the flag {@link org.tiatesting.core.distributed.DistributedRunPlanner#plan} relies
     * on to refuse planning against a selection that means "run everything" rather than "nothing
     * was impacted".
     */
    @Test
    void constructor_setsRunAllTestsTrue(){
        // given
        Set<String> testsToRun = new HashSet<>();
        Set<String> testsToIgnore = new HashSet<>();

        // when
        TestSelectorResult result = new TestSelectorResult(testsToRun, testsToIgnore, null,
                0L, Collections.emptySet(), 0L, Collections.emptyMap(), 0L, 0L, true);

        // then
        assertEquals(true, result.isRunAllTests());
    }

    /**
     * Verifies that a seed run and a "nothing was impacted" selection - the two opposite
     * instructions {@code runAllTests} exists to tell apart, both carrying an empty
     * {@code testsToRun} and an empty {@code testsToIgnore} - are not equal, and do not share a
     * hash code either. Comparing only the two suite sets made them indistinguishable, which is
     * exactly the confusion the flag was added to prevent.
     */
    @Test
    void equals_distinguishesASeedRunFromANothingImpactedSelection(){
        // given
        TestSelectorResult seedRun = new TestSelectorResult(new HashSet<>(), new HashSet<>(), null,
                0L, Collections.emptySet(), 0L, Collections.emptyMap(), 0L, 0L, true);

        // when
        TestSelectorResult nothingImpacted = new TestSelectorResult(new HashSet<>(), new HashSet<>(),
                null, 0L, Collections.emptySet(), 0L, Collections.emptyMap(), 0L, 0L, false);

        // then
        assertNotEquals(seedRun, nothingImpacted,
                "a seed run means run everything and an empty selection means run nothing - they "
                        + "must not compare equal");
        assertNotEquals(seedRun.hashCode(), nothingImpacted.hashCode());
    }

    /**
     * Verifies that two results carrying the same selection decision still compare equal, so
     * adding {@code runAllTests} to the comparison narrowed it only where it had to.
     */
    @Test
    void equals_matchesTwoResultsCarryingTheSameSelection(){
        // given
        Set<String> testsToRun = new HashSet<>(Collections.singletonList("tracked1"));
        Set<String> testsToIgnore = new HashSet<>(Collections.singletonList("ignored1"));

        // when
        TestSelectorResult first = new TestSelectorResult(new HashSet<>(testsToRun),
                new HashSet<>(testsToIgnore), null, 100L, Collections.emptySet(), 0L,
                Collections.emptyMap(), 0L, 0L, false);
        TestSelectorResult second = new TestSelectorResult(new HashSet<>(testsToRun),
                new HashSet<>(testsToIgnore), null, 999L, Collections.emptySet(), 0L,
                Collections.emptyMap(), 0L, 0L, false);

        // then
        assertEquals(first, second, "the estimate fields are not part of the selection decision");
        assertEquals(first.hashCode(), second.hashCode());
    }
}
