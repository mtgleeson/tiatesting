package org.tiatesting.core.staticselection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TiaData;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluates {@link StaticTestSelectionRule}s against the set of changed file paths and the
 * loaded {@link TiaData}, returning the set of tracked test suites that should be force-run.
 *
 * <p>Static rules are additive only: the {@link #resolve(Set, TiaData)} output is intended to
 * be unioned into the existing dynamic test selection by the caller. A rule can cause
 * additional tests to run; it can never cause a test to be skipped.
 *
 * <p>Modes {@link StaticTestSelectionRuleMode#RUN_ALL} and
 * {@link StaticTestSelectionRuleMode#SUITE_NAMES} are implemented. The constructor of
 * {@link StaticTestSelectionRule} rejects {@link StaticTestSelectionRuleMode#ANNOTATIONS_TAGS}
 * outright so it never reaches this resolver.
 *
 * <p>For SUITE_NAMES resolution a {@link SuiteNameIndex} is built lazily on first use and
 * cached for the life of the resolver instance — typically one per test selection run.
 */
public class StaticTestSelectionResolver {

    private static final Logger log = LoggerFactory.getLogger(StaticTestSelectionResolver.class);

    private final StaticTestSelectionConfig config;

    /**
     * Lazy {@link SuiteNameIndex}, built on first SUITE_NAMES rule hit and cached. Bound to
     * the first {@link TiaData} passed to {@link #resolve(Set, TiaData)} or
     * {@link #warnOnEmptyRules(TiaData)}; callers must pass the same {@code tiaData} to both
     * methods within a run.
     */
    private SuiteNameIndex suiteNameIndex;

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
     * Emit a WARN log line for every rule (or every pattern, in the case of SUITE_NAMES) that
     * resolves to zero test suites in the current {@link TiaData} snapshot, regardless of
     * whether the rule's file-path regex would match. Helps users spot misconfigured rules
     * early - typoed regexes, patterns that no longer match any tracked suite, etc.
     *
     * <p>For mode {@link StaticTestSelectionRuleMode#RUN_ALL} a single WARN per rule is emitted
     * when the Tia data has no tracked suites. For mode
     * {@link StaticTestSelectionRuleMode#SUITE_NAMES} one WARN per pattern is emitted when
     * that specific pattern matches no tracked suite; this lets a rule with a mix of working
     * and broken patterns still surface the broken ones individually.
     *
     * <p>Only invoked once per test-selection run; not called from {@link #resolve(Set, TiaData)}
     * so the warnings are not duplicated when the rule actually fires.
     *
     * @param tiaData the loaded Tia data used to resolve each rule's potential suite set.
     */
    public void warnOnEmptyRules(final TiaData tiaData) {
        for (StaticTestSelectionRule rule : config.getRules()) {
            switch (rule.getMode()) {
                case RUN_ALL:
                    warnIfRunAllRuleResolvesEmpty(rule, tiaData);
                    break;
                case SUITE_NAMES:
                    warnPerPatternThatMatchesNothing(rule, tiaData);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Emit a WARN for a {@link StaticTestSelectionRuleMode#RUN_ALL} rule when the Tia data has
     * no tracked test suites - the rule could fire but would force-run nothing.
     *
     * @param rule the RUN_ALL rule.
     * @param tiaData the loaded Tia data.
     */
    private void warnIfRunAllRuleResolvesEmpty(final StaticTestSelectionRule rule, final TiaData tiaData) {
        Map<String, ?> tracked = tiaData.getTestSuitesTracked();
        if (tracked == null || tracked.isEmpty()) {
            log.warn("Static test selection rule '{}' resolves to 0 test suites in the current Tia data snapshot. "
                    + "Check the rule configuration if this is unexpected.", rule.getName());
        }
    }

    /**
     * Emit a WARN per pattern in a {@link StaticTestSelectionRuleMode#SUITE_NAMES} rule that
     * matches no tracked suite. Patterns inside the same rule are evaluated independently so
     * a rule with a mix of working and broken patterns still surfaces the broken ones.
     *
     * @param rule the SUITE_NAMES rule.
     * @param tiaData the loaded Tia data.
     */
    private void warnPerPatternThatMatchesNothing(final StaticTestSelectionRule rule, final TiaData tiaData) {
        SuiteNameIndex index = getSuiteNameIndex(tiaData);
        Set<String> fqns = index.getFqns();
        Map<String, List<String>> simpleNameToFqns = index.getSimpleNameToFqns();
        for (Pattern pattern : rule.getSuiteNamePatterns()) {
            if (!patternMatchesAnySuite(pattern, simpleNameToFqns, fqns)) {
                log.warn("Static test selection rule '{}' pattern '{}' matched no tracked test suites.",
                        rule.getName(), pattern.pattern());
            }
        }
    }

    /**
     * Test whether a single suite-name pattern matches any simple name or FQN in the index.
     * Short-circuits on the first hit.
     *
     * @param pattern the compiled suite-name regex.
     * @param simpleNameToFqns the simple-name lookup map.
     * @param fqns the FQN set.
     * @return {@code true} if the pattern matches at least one entry.
     */
    private boolean patternMatchesAnySuite(final Pattern pattern,
                                           final Map<String, List<String>> simpleNameToFqns,
                                           final Set<String> fqns) {
        for (String simpleName : simpleNameToFqns.keySet()) {
            if (pattern.matcher(simpleName).find()) {
                return true;
            }
        }
        for (String fqn : fqns) {
            if (pattern.matcher(fqn).find()) {
                return true;
            }
        }
        return false;
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
                return resolveSuiteNamesRule(rule, tiaData);
            default:
                return Collections.emptySet();
        }
    }

    /**
     * Resolve a {@link StaticTestSelectionRuleMode#SUITE_NAMES} rule against the suite name
     * index. Each pattern is matched via {@link java.util.regex.Matcher#find()} against both
     * the simple class name and the fully qualified name of every tracked suite; either match
     * counts as a hit and contributes the FQN(s) to the resolved set.
     *
     * <p>Per-pattern empty-resolution warnings are emitted by
     * {@link #warnOnEmptyRules(TiaData)} and not duplicated here.
     *
     * @param rule the SUITE_NAMES rule being resolved.
     * @param tiaData the loaded Tia data used to lazily build the suite name index.
     * @return the union of FQNs matched by any of the rule's patterns.
     */
    private Set<String> resolveSuiteNamesRule(final StaticTestSelectionRule rule, final TiaData tiaData) {
        SuiteNameIndex index = getSuiteNameIndex(tiaData);
        Map<String, List<String>> simpleNameToFqns = index.getSimpleNameToFqns();
        Set<String> fqns = index.getFqns();
        if (fqns.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> matched = new HashSet<>();
        for (Pattern pattern : rule.getSuiteNamePatterns()) {
            for (Map.Entry<String, List<String>> entry : simpleNameToFqns.entrySet()) {
                if (pattern.matcher(entry.getKey()).find()) {
                    matched.addAll(entry.getValue());
                }
            }
            for (String fqn : fqns) {
                if (pattern.matcher(fqn).find()) {
                    matched.add(fqn);
                }
            }
        }
        return matched;
    }

    /**
     * Build (or return the cached) {@link SuiteNameIndex} for {@code tiaData}.
     *
     * @param tiaData the loaded Tia data.
     * @return the lazily-built index.
     */
    private SuiteNameIndex getSuiteNameIndex(final TiaData tiaData) {
        if (suiteNameIndex == null) {
            suiteNameIndex = new SuiteNameIndex(tiaData);
        }
        return suiteNameIndex;
    }
}
