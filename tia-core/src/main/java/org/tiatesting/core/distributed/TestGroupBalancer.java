package org.tiatesting.core.distributed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Splits a set of selected test suites into balanced groups for a distributed run. Pure: no I/O,
 * no database, no global state, so the whole grouping policy is unit-testable in isolation.
 *
 * <p>The two modes solve different problems and therefore use different algorithms. When the
 * caller fixes the group count, the goal is to minimise the heaviest group - makespan scheduling,
 * solved here with LPT (longest processing time first). When the caller fixes a target run time,
 * the goal is to minimise the number of groups - bin packing, which arrives in a later change.
 *
 * <p>Every ordering decision is broken deterministically (by suite name, then by group number) so
 * the same inputs always produce the same plan. Two runners deriving different groupings from the
 * same selection would be undebuggable.
 */
public final class TestGroupBalancer {

    private TestGroupBalancer() { }

    /**
     * Split the suites into exactly {@code groupCount} groups, minimising the heaviest group.
     *
     * <p>Walks the suites heaviest-first and puts each into the currently-lightest group. Groups
     * beyond the number of suites come back empty rather than being dropped, because stage 4
     * turns every group into a runner and the pipeline was told to start that many.
     *
     * @param suiteWeightsMs estimated run time in ms, keyed by test suite name; may be empty
     * @param groupCount how many groups to produce; must be at least 1
     * @return the grouping, always reporting the target as met and not clamped, since a fixed
     *         group count has neither a target nor a ceiling
     * @throws IllegalArgumentException if {@code groupCount} is below 1
     */
    public static GroupingResult balanceIntoGroups(final Map<String, Long> suiteWeightsMs,
                                                   final int groupCount) {
        if (groupCount < 1) {
            throw new IllegalArgumentException("groupCount must be at least 1, was " + groupCount);
        }
        List<List<String>> groupSuites = new ArrayList<>(groupCount);
        long[] groupWeights = new long[groupCount];
        for (int i = 0; i < groupCount; i++) {
            groupSuites.add(new ArrayList<String>());
        }

        for (String suiteName : sortedByWeightDescending(suiteWeightsMs)) {
            int lightest = 0;
            for (int i = 1; i < groupCount; i++) {
                if (groupWeights[i] < groupWeights[lightest]) {
                    lightest = i;
                }
            }
            groupSuites.get(lightest).add(suiteName);
            groupWeights[lightest] += suiteWeightsMs.get(suiteName);
        }

        return new GroupingResult(toSuiteGroups(groupSuites, groupWeights), true, false);
    }

    /**
     * Order suite names by weight descending, breaking ties by name ascending. Both algorithms
     * consume this order, and the name tie-break is what makes a plan reproducible when several
     * suites weigh the same.
     *
     * @param suiteWeightsMs estimated run time in ms, keyed by test suite name
     * @return the suite names in the order they should be assigned
     */
    static List<String> sortedByWeightDescending(final Map<String, Long> suiteWeightsMs) {
        List<String> names = new ArrayList<>(suiteWeightsMs.keySet());
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int byWeight = Long.compare(suiteWeightsMs.get(right), suiteWeightsMs.get(left));
                return byWeight != 0 ? byWeight : left.compareTo(right);
            }
        });
        return names;
    }

    /**
     * Convert the balancer's parallel working structures into the immutable result type.
     *
     * @param groupSuites suite names per group, indexed by group number
     * @param groupWeights summed weight per group, indexed by group number
     * @return one {@link SuiteGroup} per entry, in group-number order
     */
    private static List<SuiteGroup> toSuiteGroups(final List<List<String>> groupSuites,
                                                  final long[] groupWeights) {
        List<SuiteGroup> groups = new ArrayList<>(groupSuites.size());
        for (int i = 0; i < groupSuites.size(); i++) {
            groups.add(new SuiteGroup(i, groupSuites.get(i), groupWeights[i]));
        }
        return groups;
    }
}
