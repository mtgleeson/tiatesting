package org.tiatesting.core.distributed;

import org.tiatesting.core.model.TestRunContribution;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the contribution a distributed group's progress report carries, for tests that only care
 * about the group's figures. Each call is a separate runner JVM, so several reports against one
 * group add up the way several Surefire forks do.
 */
public final class GroupProgressFixture {

    private static final AtomicInteger NEXT_JVM = new AtomicInteger();

    private GroupProgressFixture() {
    }

    /**
     * Build one runner JVM's first-attempt contribution to a group, naming its suites
     * {@code fixture-suite-N}; the first {@code suitesFailed} of them fail.
     *
     * @param runId the distributed run
     * @param groupNumber the group the JVM works
     * @param durationMs the JVM's plan time, which is also its wall clock
     * @param suitesRan how many suites the JVM ran
     * @param suitesFailed how many of them failed
     * @param suitesDurationMs the JVM's suite-attributable time
     * @return the single contribution row, in a list as {@code reportGroupProgress} takes it
     */
    public static List<TestRunContribution> contribution(final String runId, final int groupNumber,
                                                         final long durationMs, final int suitesRan,
                                                         final int suitesFailed, final long suitesDurationMs) {
        Set<String> failed = new LinkedHashSet<>();
        for (int i = 0; i < suitesFailed; i++) {
            failed.add("fixture-suite-" + i);
        }
        return Collections.singletonList(new TestRunContribution(runId, "fixture-jvm-" + NEXT_JVM.incrementAndGet(),
                false, groupNumber, suitesRan, failed, Collections.<String>emptySet(), durationMs,
                suitesDurationMs, 1000L, 1000L + durationMs));
    }
}
