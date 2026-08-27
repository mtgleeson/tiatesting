package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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

    private static final Logger log = LoggerFactory.getLogger(TestGroupBalancer.class);

    private TestGroupBalancer() { }

    /**
     * Derive the per-suite weights the balancer packs by, from the run-time estimate Tia already
     * computes for the selection.
     *
     * <p>The supplied per-suite times come from the existing selection estimate and already carry
     * the median fallback for suites that have never recorded a run, so nothing is recomputed
     * here. The only addition is the capture overhead: the cost of JaCoCo's per-suite coverage
     * collection, which no per-suite average includes. That is supplied as a total for the whole
     * selection, matching what the estimate reports, and divided back out here. It is added only
     * for runs that collect coverage, since a run with mapping updates off does not pay it.
     *
     * <p><b>The fixed per-JVM overhead is deliberately not here.</b> It is the same constant on
     * every group, so it cannot change which suites belong together, and folding it into the
     * weights would corrupt the capacity arithmetic in {@link #balanceForTargetRunTime} - each
     * suite would appear to carry a whole JVM's start-up cost, and a group of five suites would be
     * charged five copies of a cost paid once. It is charged once per group where a group's total
     * is assembled instead.
     *
     * @param perSuiteRunTimesMs estimated run time in ms per suite, median fallback already
     *                           applied; may be empty
     * @param totalCaptureOverheadMs the coverage-capture overhead in ms for the whole selection,
     *                               not per suite; must not be negative
     * @param collectingCoverage whether this run will collect coverage and therefore pay the
     *                           capture overhead
     * @return a new map of weights in ms keyed by suite name; the supplied map is not modified
     * @throws IllegalArgumentException if {@code totalCaptureOverheadMs} is negative
     */
    public static Map<String, Long> suiteWeights(final Map<String, Long> perSuiteRunTimesMs,
                                                 final long totalCaptureOverheadMs,
                                                 final boolean collectingCoverage) {
        if (totalCaptureOverheadMs < 0) {
            throw new IllegalArgumentException(
                    "capture overhead must not be negative, was " + totalCaptureOverheadMs);
        }
        requireNoNullWeights(perSuiteRunTimesMs);
        Map<String, Long> weights = new HashMap<>();
        if (perSuiteRunTimesMs.isEmpty()) {
            return weights;
        }
        long overheadPerSuiteMs = collectingCoverage
                ? totalCaptureOverheadMs / perSuiteRunTimesMs.size()
                : 0L;
        for (Map.Entry<String, Long> entry : perSuiteRunTimesMs.entrySet()) {
            weights.put(entry.getKey(), entry.getValue() + overheadPerSuiteMs);
        }
        return weights;
    }

    /**
     * Split the suites into exactly {@code groupCount} groups, minimising the heaviest group.
     *
     * <p>Walks the suites heaviest-first and puts each into the currently-lightest group. Groups
     * beyond the number of suites come back empty rather than being dropped, because the planner
     * turns every group into a runner and the pipeline was told to start that many.
     *
     * @param suiteWeightsMs estimated run time in ms, keyed by test suite name; may be empty
     * @param groupCount how many groups to produce; must be at least 1
     * @param fixedOverheadMs the per-JVM cost in ms each group pays once, added to every group that
     *                        was given at least one suite; must not be negative
     * @return the grouping, always reporting the target as met and not clamped, since a fixed
     *         group count has neither a target nor a ceiling
     * @throws IllegalArgumentException if {@code groupCount} is below 1 or {@code fixedOverheadMs}
     *                                  is negative
     */
    public static GroupingResult balanceIntoGroups(final Map<String, Long> suiteWeightsMs,
                                                   final int groupCount,
                                                   final long fixedOverheadMs) {
        GroupingResult result = lptIntoGroups(suiteWeightsMs, groupCount, fixedOverheadMs);
        log.debug("Distributed run grouping (fixed count): balanced {} suite(s) into {} group(s) "
                        + "by longest-processing-time, heaviest group {}ms.",
                suiteWeightsMs.size(), groupCount, result.getHeaviestGroupMs());
        logGroupAssignment(result);
        return result;
    }

    /**
     * Run the longest-processing-time balance itself, without logging the resulting assignment.
     * Split out from {@link #balanceIntoGroups} so the target-run-time path can use it as a
     * candidate re-balance - which it may then discard - without that discarded candidate being
     * logged as if it were the plan.
     *
     * @param suiteWeightsMs estimated run time in ms, keyed by test suite name; may be empty
     * @param groupCount how many groups to produce; must be at least 1
     * @param fixedOverheadMs the per-JVM cost in ms each group pays once; must not be negative
     * @return the grouping, always reporting the target as met and not clamped
     * @throws IllegalArgumentException if {@code groupCount} is below 1 or {@code fixedOverheadMs}
     *                                  is negative
     */
    private static GroupingResult lptIntoGroups(final Map<String, Long> suiteWeightsMs,
                                                final int groupCount,
                                                final long fixedOverheadMs) {
        if (groupCount < 1) {
            throw new IllegalArgumentException("groupCount must be at least 1, was " + groupCount);
        }
        requireFixedOverheadNotNegative(fixedOverheadMs);
        requireNoNullWeights(suiteWeightsMs);
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

        return new GroupingResult(toSuiteGroups(groupSuites, groupWeights, fixedOverheadMs), true,
                false, false, false);
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
     * <p><b>The target is a budget for the whole group, and a group's cost starts above zero.</b>
     * Every runner pays {@code fixedOverheadMs} before it executes a single suite, so the budget
     * available for suites is {@code target - fixed}, and a group meets the target only when its
     * suites plus that fixed cost come in under it. The fixed part still never enters the per-suite
     * weights - see {@link #suiteWeights} - it bounds the capacity and is charged once per group.
     *
     * <p>When {@code fixedOverheadMs} is at or above the target, no group count can meet it: adding
     * runners adds copies of a cost that already exceeds the budget on its own. That is reported as
     * its own miss reason rather than being folded into "a single suite is too long", because the
     * lever is different - the suite case is fixed by splitting or speeding up one suite, this one
     * cannot be fixed by any change to the selection.
     *
     * <p>Meeting the target is best effort. When {@code maxGroups} is too low, when a single suite
     * is longer than the budget, or when the fixed cost alone exceeds the target, the result still
     * contains a usable plan balanced by time and reports {@code targetMet == false} rather than
     * failing.
     *
     * @param suiteWeightsMs estimated run time in ms, keyed by test suite name; may be empty
     * @param targetRunTimeMs the wall-clock test run time to aim for, in ms; must not be negative
     * @param maxGroups an optional ceiling on the group count, or null for no ceiling; if
     *                  supplied, must be at least 1
     * @param fixedOverheadMs the per-JVM cost in ms each group pays once, before any suite runs;
     *                        must not be negative
     * @return the grouping, reporting whether the target was met and each of the three independent
     *         reasons it might not have been
     * @throws IllegalArgumentException if {@code targetRunTimeMs} or {@code fixedOverheadMs} is
     *                                  negative, or if {@code maxGroups} is non-null and below 1
     */
    public static GroupingResult balanceForTargetRunTime(final Map<String, Long> suiteWeightsMs,
                                                         final long targetRunTimeMs,
                                                         final Integer maxGroups,
                                                         final long fixedOverheadMs) {
        if (targetRunTimeMs < 0) {
            throw new IllegalArgumentException(
                    "targetRunTimeMs must not be negative, was " + targetRunTimeMs);
        }
        if (maxGroups != null && maxGroups < 1) {
            throw new IllegalArgumentException("maxGroups must be at least 1, was " + maxGroups);
        }
        requireFixedOverheadNotNegative(fixedOverheadMs);
        requireNoNullWeights(suiteWeightsMs);

        if (suiteWeightsMs.isEmpty()) {
            log.debug("Distributed run grouping (dynamic): nothing was selected, so the plan is a "
                    + "single empty group.");
            return lptIntoGroups(suiteWeightsMs, 1, fixedOverheadMs);
        }

        long heaviestSuiteMs = 0L;
        for (Long weight : suiteWeightsMs.values()) {
            if (weight > heaviestSuiteMs) {
                heaviestSuiteMs = weight;
            }
        }

        // What is left of the target once the runner's own start-up is paid for. This, not the
        // target itself, is what the suites have to fit inside.
        long suiteBudgetMs = targetRunTimeMs - fixedOverheadMs;
        // The three facts the capacity floor below and the caller's diagnostics both need, derived
        // once here and threaded through rather than re-derived from the weight map a second time.
        boolean fixedOverheadExceedsTarget = suiteBudgetMs <= 0L;
        boolean singleSuiteExceedsTarget = !fixedOverheadExceedsTarget
                && heaviestSuiteMs > suiteBudgetMs;
        // Anything that puts the target out of reach no matter how many groups are used means
        // packing to the achievable floor instead: same makespan, fewer runners.
        long capacityMs = Math.max(suiteBudgetMs, heaviestSuiteMs);

        List<List<String>> bins = firstFitDecreasing(suiteWeightsMs, capacityMs);
        boolean clampedToMaxGroups = maxGroups != null && bins.size() > maxGroups;
        int groupCount = maxGroups == null ? bins.size() : Math.min(bins.size(), maxGroups);

        GroupingResult packing;
        if (groupCount == bins.size()) {
            packing = new GroupingResult(
                    toSuiteGroups(bins, weighGroups(bins, suiteWeightsMs), fixedOverheadMs),
                    false, clampedToMaxGroups, singleSuiteExceedsTarget, fixedOverheadExceedsTarget);
            // FFD fills groups to capacity; LPT spreads them. Same group count either way, so
            // take whichever finishes sooner. It is not always LPT - see the nine-suite case.
            // Both carry the same per-group fixed cost, so it cannot decide the comparison.
            GroupingResult rebalanced = lptIntoGroups(suiteWeightsMs, groupCount, fixedOverheadMs);
            if (rebalanced.getHeaviestGroupMs() < packing.getHeaviestGroupMs()) {
                packing = rebalanced;
            }
        } else {
            // Clamped below what the packing needed. FFD's bins do not fit in the reduced count,
            // so there is nothing to compare against: minimising the heaviest group is now the
            // whole objective, which is exactly what LPT does.
            packing = lptIntoGroups(suiteWeightsMs, groupCount, fixedOverheadMs);
        }

        // The heaviest group already carries its own copy of the fixed cost, so this compares
        // against the whole target rather than the suite budget.
        boolean targetMet = packing.getHeaviestGroupMs() <= targetRunTimeMs;

        log.debug("Distributed run grouping (dynamic): packed {} suite(s) against a target of {}ms "
                        + "into {} group(s), heaviest {}ms (including {}ms of per-JVM overhead). "
                        + "First-fit needed {} group(s); ceiling {}; heaviest single suite {}ms "
                        + "against a suite budget of {}ms. Target met: {}.", suiteWeightsMs.size(),
                targetRunTimeMs, packing.getGroups().size(), packing.getHeaviestGroupMs(),
                fixedOverheadMs, bins.size(), maxGroups == null ? "none" : maxGroups,
                heaviestSuiteMs, suiteBudgetMs, targetMet);
        if (clampedToMaxGroups) {
            log.debug("Distributed run grouping: the ceiling of {} group(s) bound the plan - "
                            + "first-fit wanted {} to hit the target.", maxGroups, bins.size());
        }
        if (fixedOverheadExceedsTarget) {
            log.debug("Distributed run grouping: each runner pays {}ms of per-JVM overhead before "
                            + "it runs a single suite, which is at or above the {}ms target, so no "
                            + "group count can reach the target. The suites were packed to the "
                            + "achievable floor of {}ms instead.", fixedOverheadMs, targetRunTimeMs,
                    capacityMs);
        }
        if (singleSuiteExceedsTarget) {
            log.debug("Distributed run grouping: the heaviest single suite takes {}ms, longer than "
                            + "the {}ms left of the target once each runner's {}ms of per-JVM "
                            + "overhead is paid for, so no group count can reach the target and the "
                            + "suites were packed to the achievable floor of {}ms instead.",
                    heaviestSuiteMs, suiteBudgetMs, fixedOverheadMs, capacityMs);
        }
        logGroupAssignment(packing);

        return new GroupingResult(packing.getGroups(), targetMet, clampedToMaxGroups,
                singleSuiteExceedsTarget, fixedOverheadExceedsTarget);
    }

    /**
     * Log which suites each group of the chosen plan owns, so a build whose runners took wildly
     * different times can be traced back to the assignment that produced them. At DEBUG because
     * the suite names are unbounded - a large selection would otherwise dominate the build output.
     *
     * @param result the grouping that was chosen, not a candidate that was discarded
     */
    private static void logGroupAssignment(final GroupingResult result) {
        if (!log.isDebugEnabled()) {
            return;
        }
        for (SuiteGroup group : result.getGroups()) {
            log.debug("Distributed run grouping: group {} gets {} suite(s), ~{}ms: {}",
                    group.getGroupNumber(), group.getSuiteNames().size(), group.getEstimatedMs(),
                    group.getSuiteNames());
        }
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
    private static List<String> sortedByWeightDescending(final Map<String, Long> suiteWeightsMs) {
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
     * <p>This is where the fixed per-JVM cost is charged, once per group - never in the weights the
     * packing decided on. A group that was given no suites is charged nothing: an empty group runs
     * no tests, and charging it would put a non-zero estimate on a build that has nothing to do.
     *
     * @param groupSuites suite names per group, indexed by group number
     * @param groupWeights summed suite weight per group, indexed by group number, without the fixed
     *                     per-JVM cost
     * @param fixedOverheadMs the per-JVM cost in ms to add to each group that has at least one suite
     * @return one {@link SuiteGroup} per entry, in group-number order
     */
    private static List<SuiteGroup> toSuiteGroups(final List<List<String>> groupSuites,
                                                  final long[] groupWeights,
                                                  final long fixedOverheadMs) {
        List<SuiteGroup> groups = new ArrayList<>(groupSuites.size());
        for (int i = 0; i < groupSuites.size(); i++) {
            long estimatedMs = groupSuites.get(i).isEmpty()
                    ? groupWeights[i]
                    : groupWeights[i] + fixedOverheadMs;
            groups.add(new SuiteGroup(i, groupSuites.get(i), estimatedMs));
        }
        return groups;
    }

    /**
     * Reject a negative per-JVM overhead at the entry point, rather than letting it silently shrink
     * a group's estimate or, in the dynamic path, inflate the suite budget above the target.
     *
     * @param fixedOverheadMs the per-JVM cost in ms to validate
     * @throws IllegalArgumentException if {@code fixedOverheadMs} is negative
     */
    private static void requireFixedOverheadNotNegative(final long fixedOverheadMs) {
        if (fixedOverheadMs < 0) {
            throw new IllegalArgumentException(
                    "fixed overhead must not be negative, was " + fixedOverheadMs);
        }
    }

    /**
     * Reject a null weight before any comparison or arithmetic in this class touches it.
     * Unboxing a null {@code Long} throws a bare {@link NullPointerException} that gives no clue
     * which suite is at fault; this turns that into a message naming the suite, checked once at
     * the entry point of each public method rather than scattered across the comparator and the
     * packing loops that would otherwise hit it.
     *
     * @param weightsMs weight in ms keyed by suite name, checked for null values
     * @throws IllegalArgumentException if any value in the map is null, naming the suite it
     *                                  belongs to
     */
    private static void requireNoNullWeights(final Map<String, Long> weightsMs) {
        for (Map.Entry<String, Long> entry : weightsMs.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "weight must not be null for suite: " + entry.getKey());
            }
        }
    }
}
