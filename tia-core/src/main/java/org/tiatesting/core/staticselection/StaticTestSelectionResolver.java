package org.tiatesting.core.staticselection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TiaData;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates {@link StaticTestSelectionRule}s against the set of changed file paths and the
 * loaded {@link TiaData}, returning the set of tracked test suites that should be force-run.
 *
 * <p>Static rules are additive only: the {@link #resolve(Set, TiaData)} output is intended to
 * be unioned into the existing dynamic test selection by the caller. A rule can cause
 * additional tests to run; it can never cause a test to be skipped.
 *
 * <p>Stage 2 implements mode {@link StaticTestSelectionRuleMode#RUN_ALL}. Mode
 * {@link StaticTestSelectionRuleMode#SUITE_NAMES} is reserved for stage 3 and currently
 * resolves to an empty set; the constructor of {@link StaticTestSelectionRule} rejects
 * {@link StaticTestSelectionRuleMode#ANNOTATIONS_TAGS} outright so it never reaches this
 * resolver.
 */
public class StaticTestSelectionResolver {

    private static final Logger log = LoggerFactory.getLogger(StaticTestSelectionResolver.class);

    private final StaticTestSelectionConfig config;

    /**
     * Construct a resolver bound to a specific configuration.
     *
     * @param config the static test selection config; must be non-null and enabled (callers
     *               typically guard with {@link StaticTestSelectionConfig#isEnabled()} before
     *               constructing the resolver).
     */
    public StaticTestSelectionResolver(final StaticTestSelectionConfig config) {
        this.config = config;
    }

    /**
     * Resolve every rule against the changed file paths and return the union of forced
     * suite names. For each rule whose file-path regex matches at least one changed path,
     * the rule's mode-specific resolution is applied to {@code tiaData} and the result is
     * unioned in. Rules whose file-path regex matches no changed paths contribute nothing.
     *
     * <p>Per fired rule, an INFO log line records the rule name, matched-file count, and
     * forced-suite count. Per-match logging is deliberately omitted to keep the read path
     * cheap on high-volume commit ranges.
     *
     * @param changedPaths the repo-relative, forward-slash-normalised paths of every file
     *                     changed in the current commit range / local workspace.
     * @param tiaData the loaded Tia data; used to resolve forced suite sets.
     * @return the union of forced suite names; never {@code null}, may be empty.
     */
    public Set<String> resolve(final Set<String> changedPaths, final TiaData tiaData) {
        Set<String> forced = new HashSet<>();
        if (changedPaths == null || changedPaths.isEmpty()) {
            return forced;
        }

        for (StaticTestSelectionRule rule : config.getRules()) {
            int matchedFileCount = countMatchingPaths(rule, changedPaths);
            if (matchedFileCount == 0) {
                log.debug("Static test selection rule '{}' did not match any changes, skipping.", rule.getName());
                continue;
            }
            Set<String> ruleForced = resolveRule(rule, tiaData);
            log.info("Static test selection rule '{}' matched {} changed file(s), forcing {} test suite(s).",
                    rule.getName(), matchedFileCount, ruleForced.size());
            forced.addAll(ruleForced);
        }
        return forced;
    }

    /**
     * Emit a WARN log line for every rule that resolves to zero test suites in the current
     * {@link TiaData} snapshot, regardless of whether its file-path regex would match. Helps
     * users spot misconfigured rules early - typoed regexes, configs that no longer match
     * any tracked suite, etc.
     *
     * <p>Only invoked once per test-selection run; not called from {@link #resolve(Set, TiaData)}.
     *
     * @param tiaData the loaded Tia data used to resolve each rule's potential suite set.
     */
    public void warnOnEmptyRules(final TiaData tiaData) {
        for (StaticTestSelectionRule rule : config.getRules()) {
            Set<String> potential = resolveRule(rule, tiaData);
            if (potential.isEmpty()) {
                log.warn("Static test selection rule '{}' resolves to 0 test suites in the current Tia data snapshot. "
                        + "Check the rule configuration if this is unexpected.", rule.getName());
            }
        }
    }

    /**
     * Count how many entries in {@code changedPaths} match the rule's file-path regex via
     * {@link java.util.regex.Matcher#find()} (substring match).
     *
     * @param rule the rule whose file-path pattern is applied.
     * @param changedPaths the changed file paths to test.
     * @return the count of matching paths.
     */
    private int countMatchingPaths(final StaticTestSelectionRule rule, final Set<String> changedPaths) {
        int count = 0;
        for (String path : changedPaths) {
            if (rule.getFilePathPattern().matcher(path).find()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Resolve a single rule into the set of suite names it would force-run if its file-path
     * regex matched at least one changed path.
     *
     * @param rule the rule to resolve.
     * @param tiaData the loaded Tia data.
     * @return the rule's potential forced suite set.
     */
    private Set<String> resolveRule(final StaticTestSelectionRule rule, final TiaData tiaData) {
        switch (rule.getMode()) {
            case RUN_ALL:
                Map<String, ?> tracked = tiaData.getTestSuitesTracked();
                return (tracked == null || tracked.isEmpty())
                        ? Collections.emptySet()
                        : new HashSet<>(tracked.keySet());
            case SUITE_NAMES:
                // Stage 3 wires regex resolution against an indexed suite name lookup.
                return Collections.emptySet();
            default:
                return Collections.emptySet();
        }
    }
}
