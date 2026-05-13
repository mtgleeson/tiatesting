package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        long estimatedRunTimeMs = 1500L;
        long medianRunTimeMsAppliedToMissing = 250L;

        // when
        TestSelectorResult result = new TestSelectorResult(testsToRun, testsToIgnore, null,
                estimatedRunTimeMs, withoutStats, medianRunTimeMsAppliedToMissing);

        // then
        assertSame(testsToRun, result.getTestsToRun());
        assertSame(testsToIgnore, result.getTestsToIgnore());
        assertEquals(estimatedRunTimeMs, result.getEstimatedRunTimeMs());
        assertSame(withoutStats, result.getSelectedTestsWithoutStats());
        assertEquals(medianRunTimeMsAppliedToMissing, result.getMedianRunTimeMsAppliedToMissing());
    }
}
