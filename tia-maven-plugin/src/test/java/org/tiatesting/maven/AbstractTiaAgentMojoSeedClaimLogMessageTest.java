package org.tiatesting.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cover {@link AbstractTiaAgentMojo#seedRunClaimLogMessage(String, String, int, int)}: the exact
 * text of the seed-run claim-log message a distributed runner prints when it claims a group of a
 * seed run. Split out from {@link AbstractTiaAgentMojoDistributedTest} so the two wordings - split
 * and fallback - can be asserted directly against the pure message-building function, rather than
 * only observed indirectly in console output from a full {@code execute()} run.
 */
class AbstractTiaAgentMojoSeedClaimLogMessageTest {

    /**
     * Verify the split-seed wording: a claimed group carrying real suite names - suites
     * discovered on disk and divided across the groups - reports the number it will run and names
     * itself a seed run whose groups together record the mapping.
     */
    @Test
    void seedRunClaimLogMessage_splitSeed_producesTheSplitWording() {
        // given - a claimed group carrying two real suite names, so assignedSuiteCount is positive
        int assignedSuiteCount = 2;

        // when
        String message = AbstractTiaAgentMojo.seedRunClaimLogMessage("run-1", "runner-a", 0,
                assignedSuiteCount);

        // then
        assertEquals("Tia distributed run 'run-1': runner 'runner-a' claimed group 0 and will run "
                + "2 test suite(s). This is a seed run - no stored mapping for this branch yet, "
                + "so its suites were discovered on disk and split across the groups; together "
                + "the groups record the mapping the next build plans from.", message);
    }

    /**
     * Verify the fallback-seed wording: a claimed group assigned no suite names at all reports
     * that this runner will execute every test it discovers, rather than the misleading "will run
     * 0 test suite(s)".
     */
    @Test
    void seedRunClaimLogMessage_fallbackSeed_producesTheFallbackWording() {
        // given - a claimed group assigned no suite names, so assignedSuiteCount is zero
        int assignedSuiteCount = 0;

        // when
        String message = AbstractTiaAgentMojo.seedRunClaimLogMessage("run-2", "runner-b", 0,
                assignedSuiteCount);

        // then
        assertEquals("Tia distributed run 'run-2': runner 'runner-b' claimed group 0. This is a "
                + "seed run - there is no stored mapping for this branch yet, so the plan carries "
                + "no suite names and this runner will execute every test it discovers and record "
                + "the mapping the next build plans from.", message);
    }
}
