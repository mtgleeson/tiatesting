package org.tiatesting.core.distributed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One group of test suites within a distributed run plan: the suites one runner will execute,
 * and the estimated time they will take. Produced by {@link TestGroupBalancer}; stage 4's planner
 * projects it onto the persisted group and suite-assignment rows.
 */
public final class SuiteGroup {

    private final int groupNumber;
    private final List<String> suiteNames;
    private final long estimatedMs;

    /**
     * Create a group, defensively copying the suite names so the balancer's working lists cannot
     * later mutate a returned result.
     *
     * @param groupNumber zero-based index of this group within the run
     * @param suiteNames the test suite names assigned to this group
     * @param estimatedMs the summed estimated run time of those suites, in ms
     */
    public SuiteGroup(int groupNumber, List<String> suiteNames, long estimatedMs) {
        this.groupNumber = groupNumber;
        this.suiteNames = Collections.unmodifiableList(new ArrayList<>(suiteNames));
        this.estimatedMs = estimatedMs;
    }

    /** @return the zero-based index of this group within the run */
    public int getGroupNumber() { return groupNumber; }

    /** @return the suite names assigned to this group, unmodifiable */
    public List<String> getSuiteNames() { return suiteNames; }

    /** @return the summed estimated run time of this group's suites, in ms */
    public long getEstimatedMs() { return estimatedMs; }

    /**
     * Diagnostic rendering naming the group, how many suites it holds and its weight.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "SuiteGroup{number=" + groupNumber + ", suites=" + suiteNames.size()
                + ", estimatedMs=" + estimatedMs + "}";
    }
}
