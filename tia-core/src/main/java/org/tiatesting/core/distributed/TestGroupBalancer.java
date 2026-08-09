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
 * the goal is to minimise the number of groups - bin packing, solved here with FFD (first-fit
 * decreasing) to choose the group count, followed by an LPT re-balance at that count when it
 * produces a lighter heaviest group.
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
     * Split the suites into the fewest groups that keep every group at or under
     * {@code targetRunTimeMs}.
     *
     * <p>This is bin packing, not makespan scheduling: the target fixes the capacity and the
     * group count is what is being minimised. Uses FFD (first-fit decreasing) to choose the group
     * count, then re-balances with LPT at that count and keeps whichever packing has the lighter
     * heaviest group - FFD fills groups to capacity, so the re-balance usually produces a faster
     * build for the same number of runners, but not always.
     *
     * <p>Meeting the target is best effort. When {@code maxGroups} is too low, or when a single
     * suite is longer than the whole target, the result still contains a usable plan balanced by
     * time and reports {@code targetMet == false} rather than failing.
     *
     * @param suiteWeightsMs estimated run time in ms, keyed by test suite name; may be empty
     * @param targetRunTimeMs the wall-clock test run time to aim for, in ms
     * @param maxGroups an optional ceiling on the group count, or null for no ceiling
     * @return the grouping, reporting whether the target was met and whether the ceiling bound it
     */
    public static GroupingResult balanceForTargetRunTime(final Map<String, Long> suiteWeightsMs,
                                                         final long targetRunTimeMs,
                                                         final Integer maxGroups) {
        if (suiteWeightsMs.isEmpty()) {
            return balanceIntoGroups(suiteWeightsMs, 1);
        }

        long heaviestSuiteMs = 0L;
        for (Long weight : suiteWeightsMs.values()) {
            if (weight > heaviestSuiteMs) {
                heaviestSuiteMs = weight;
            }
        }
        // A suite longer than the whole target puts the target out of reach no matter how many
        // groups are used, so pack to the achievable floor instead: same makespan, fewer runners.
        long capacityMs = Math.max(targetRunTimeMs, heaviestSuiteMs);

        List<List<String>> bins = firstFitDecreasing(suiteWeightsMs, capacityMs);
        boolean clampedToMaxGroups = maxGroups != null && bins.size() > maxGroups;
        int groupCount = maxGroups == null ? bins.size() : Math.min(bins.size(), maxGroups);

        GroupingResult packing;
        if (groupCount == bins.size()) {
            packing = new GroupingResult(toSuiteGroups(bins, weighGroups(bins, suiteWeightsMs)),
                    false, clampedToMaxGroups);
            // FFD fills groups to capacity; LPT spreads them. Same group count either way, so
            // take whichever finishes sooner. It is not always LPT - see the nine-suite case.
            GroupingResult rebalanced = balanceIntoGroups(suiteWeightsMs, groupCount);
            if (rebalanced.getHeaviestGroupMs() < packing.getHeaviestGroupMs()) {
                packing = rebalanced;
            }
        } else {
            // Clamped below what the packing needed. FFD's bins do not fit in the reduced count,
            // so there is nothing to compare against: minimising the heaviest group is now the
            // whole objective, which is exactly what LPT does.
            packing = balanceIntoGroups(suiteWeightsMs, groupCount);
        }

        boolean targetMet = packing.getHeaviestGroupMs() <= targetRunTimeMs;
        return new GroupingResult(packing.getGroups(), targetMet, clampedToMaxGroups);
    }

    /**
     * Pack the suites into groups of at most {@code capacityMs}, opening a new group only when a
     * suite fits none of the existing ones. Walks the suites heaviest-first, which is what makes
     * first-fit produce near-optimal group counts.
     *
     * <p>A suite heavier than {@code capacityMs} still gets its own group rather than being
     * dropped; callers raise the capacity to the heaviest suite so this cannot normally happen.
     *
     * @param suiteWeightsMs estimated run time in ms, keyed by test suite name
     * @param capacityMs the maximum weight a group should reach
     * @return suite names per group, indexed by group number
     */
    private static List<List<String>> firstFitDecreasing(final Map<String, Long> suiteWeightsMs,
                                                         final long capacityMs) {
        List<List<String>> bins = new ArrayList<>();
        List<Long> binWeights = new ArrayList<>();

        for (String suiteName : sortedByWeightDescending(suiteWeightsMs)) {
            long weight = suiteWeightsMs.get(suiteName);
            boolean placed = false;
            for (int i = 0; i < bins.size(); i++) {
                if (binWeights.get(i) + weight <= capacityMs) {
                    bins.get(i).add(suiteName);
                    binWeights.set(i, binWeights.get(i) + weight);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<String> bin = new ArrayList<>();
                bin.add(suiteName);
                bins.add(bin);
                binWeights.add(weight);
            }
        }
        return bins;
    }

    /**
     * Sum each group's suite weights, so a packing built as name lists can be turned into the
     * result type without the caller tracking weights alongside it.
     *
     * @param groupSuites suite names per group, indexed by group number
     * @param suiteWeightsMs estimated run time in ms, keyed by test suite name
     * @return summed weight per group, indexed by group number
     */
    private static long[] weighGroups(final List<List<String>> groupSuites,
                                      final Map<String, Long> suiteWeightsMs) {
        long[] weights = new long[groupSuites.size()];
        for (int i = 0; i < groupSuites.size(); i++) {
            long total = 0L;
            for (String suiteName : groupSuites.get(i)) {
                total += suiteWeightsMs.get(suiteName);
            }
            weights[i] = total;
        }
        return weights;
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
