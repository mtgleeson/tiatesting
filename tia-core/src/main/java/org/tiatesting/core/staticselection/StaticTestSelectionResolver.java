package org.tiatesting.core.staticselection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.staticselection.pom.PomChange;
import org.tiatesting.core.staticselection.pom.PomChangeAnalyzer;
import org.tiatesting.core.staticselection.pom.PomChangeCategory;
import org.tiatesting.core.staticselection.pom.PomChangeReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluates {@link StaticTestSelectionRule}s against the set of changed file paths and the
 * tracked test suites, returning the set of tracked suites that should be force-run.
 *
 * <p>Static rules are additive only: the forced suites in the
 * {@link #resolve(Set, Map, PomChangeAnalyzer)} output are intended to be unioned into the existing
 * dynamic test selection by the caller. A rule can cause additional tests to run; it can never
 * cause a test to be skipped.
 *
 * <p>A POM-aware rule (one with {@link StaticTestSelectionRule#getPomChanges() pomChanges}) fires
 * only when a matched POM's changes fall into one of its categories, as classified by a
 * {@link PomChangeAnalyzer}. POM content is fetched only when such a rule's path pattern matches.
 *
 * <p>Modes {@link StaticTestSelectionRuleMode#RUN_ALL} and
 * {@link StaticTestSelectionRuleMode#SUITE_NAMES} are implemented. The constructor of
 * {@link StaticTestSelectionRule} rejects {@link StaticTestSelectionRuleMode#ANNOTATIONS_TAGS}
 * outright so it never reaches this resolver.
 *
 * <p>The resolver consumes only the tracked-suites map (names + stats, no coverage edges) -
 * the cheap metadata read the targeted select-tests path already performs - so static rule
 * evaluation never requires the full mapping to be loaded.
 *
 * <p>For SUITE_NAMES resolution a {@link SuiteNameIndex} is built lazily on first use and
 * cached for the life of the resolver instance — typically one per test selection run.
 */
public class StaticTestSelectionResolver {

    private static final Logger log = LoggerFactory.getLogger(StaticTestSelectionResolver.class);

    /** How many POM changes a rule's INFO line lists before summarising the rest as a count. */
    private static final int MAX_LOGGED_POM_CHANGES = 3;

    private final StaticTestSelectionConfig config;

    /**
     * Lazy {@link SuiteNameIndex}, built on first SUITE_NAMES rule hit and cached. Bound to
     * the first tracked-suites map passed to {@link #resolve(Set, Map, PomChangeAnalyzer)} or
     * {@link #warnOnEmptyRules(Map)}; callers must pass the same map to both methods within
     * a run.
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
     * suite names. Which rules fire is decided by {@link #evaluateRules(Set, PomChangeAnalyzer)};
     * each fired rule's mode-specific resolution is applied to the tracked suites and unioned in.
     *
     * <p>Per fired rule, an INFO log line records the rule name, matched-file count, and
     * forced-suite count.
     *
     * @param changedPaths the repo-relative, forward-slash-normalised paths of every file
     *                     changed in the current commit range / local workspace.
     * @param testSuitesTracked the tracked test suites keyed by suite name; used to resolve
     *                          forced suite sets.
     * @param pomAnalyzer classifies the changes to matched POMs for POM-aware rules; may be
     *                    {@code null} only when the config has no POM-aware rules.
     * @return the union of forced suite names plus one {@link TestRunTrigger} per fired rule;
     *         never {@code null}, both may be empty.
     */
    public StaticTestSelectionResult resolve(final Set<String> changedPaths,
                                             final Map<String, TestSuiteTracker> testSuitesTracked,
                                             final PomChangeAnalyzer pomAnalyzer) {
        Set<String> forced = new HashSet<>();
        List<TestRunTrigger> ruleTriggers = new ArrayList<>();
        for (FiredStaticRule fired : evaluateRules(changedPaths, pomAnalyzer)) {
            StaticTestSelectionRule rule = fired.getRule();
            Set<String> ruleForced = resolveRule(rule, testSuitesTracked);
            log.info("Static test selection rule '{}' matched {} changed file(s), forcing {} test suite(s).",
                    rule.getName(), fired.getMatchedFileCount(), ruleForced.size());
            forced.addAll(ruleForced);
            ruleTriggers.add(new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, fired.getTriggerName(), null,
                    ruleForced.size()));
        }
        return new StaticTestSelectionResult(forced, ruleTriggers);
    }

    /**
     * Decide which rules fire for the changed file paths. A plain path rule fires when its
     * file-path regex matches at least one changed path. A POM-aware rule additionally needs a
     * matched file's changes to include one of its {@link StaticTestSelectionRule#getPomChanges()
     * categories}: the POMs matched by any POM-aware rule are classified together in one
     * {@link PomChangeAnalyzer#analyze} call, so content is fetched only when such a rule matches.
     * Shared by test selection and the library publish stamper so both evaluate rules the same way.
     *
     * <p>For each POM-aware rule that matched, an INFO line explains the outcome: the changes that
     * made it fire, or the changes it saw when it did not (e.g. a release version bump, all inert).
     * When DEBUG is enabled each rule's matched files are listed; that detail is gathered only
     * when DEBUG is on so the read path stays cheap on high-volume commit ranges.
     *
     * @param changedPaths the repo-relative, forward-slash-normalised changed file paths.
     * @param pomAnalyzer classifies the changes to matched POMs; may be {@code null} only when the
     *                    config has no POM-aware rules.
     * @return the fired rules, in configuration order; never {@code null}.
     * @throws IllegalStateException if a POM-aware rule matched and {@code pomAnalyzer} is {@code null}.
     */
    public List<FiredStaticRule> evaluateRules(final Set<String> changedPaths, final PomChangeAnalyzer pomAnalyzer) {
        List<FiredStaticRule> fired = new ArrayList<>();
        if (changedPaths == null || changedPaths.isEmpty()) {
            return fired;
        }

        boolean debugEnabled = log.isDebugEnabled();
        Map<StaticTestSelectionRule, List<String>> pomRuleMatches = new LinkedHashMap<>();
        Set<String> matchedPoms = new LinkedHashSet<>();
        for (StaticTestSelectionRule rule : config.getRules()) {
            if (rule.isPomAware()) {
                List<String> matchedPaths = matchingPaths(rule, changedPaths);
                if (!matchedPaths.isEmpty()) {
                    pomRuleMatches.put(rule, matchedPaths);
                    matchedPoms.addAll(matchedPaths);
                }
            }
        }
        Map<String, PomChangeReport> reports = Collections.emptyMap();
        if (!matchedPoms.isEmpty()) {
            if (pomAnalyzer == null) {
                throw new IllegalStateException("A POM-aware static test selection rule matched, but no POM change"
                        + " analyzer was supplied.");
            }
            reports = pomAnalyzer.analyze(matchedPoms);
        }

        for (StaticTestSelectionRule rule : config.getRules()) {
            if (rule.isPomAware()) {
                List<String> matchedPaths = pomRuleMatches.get(rule);
                if (matchedPaths == null) {
                    log.debug("Static test selection rule '{}' did not match any changes, skipping.", rule.getName());
                    continue;
                }
                FiredStaticRule firedPomRule = evaluatePomRule(rule, matchedPaths, reports);
                if (firedPomRule != null) {
                    fired.add(firedPomRule);
                }
                continue;
            }

            List<String> matchedPaths = debugEnabled ? matchingPaths(rule, changedPaths) : null;
            int matchedFileCount = debugEnabled ? matchedPaths.size() : countMatchingPaths(rule, changedPaths);
            if (matchedFileCount == 0) {
                log.debug("Static test selection rule '{}' did not match any changes, skipping.", rule.getName());
                continue;
            }
            if (debugEnabled) {
                log.debug("Static test selection rule '{}' matched changed file(s): {}", rule.getName(), matchedPaths);
            }
            fired.add(new FiredStaticRule(rule, matchedFileCount, Collections.<PomChangeCategory>emptySet()));
        }
        return fired;
    }

    /**
     * Decide whether a POM-aware rule fires for the POMs it matched, and log why.
     *
     * @param rule the POM-aware rule.
     * @param matchedPaths the changed POM paths the rule's path pattern matched.
     * @param reports the classified changes of every matched POM, keyed by path.
     * @return the fired rule, or {@code null} when none of the matched POMs' changes is in the
     *         rule's categories.
     */
    private FiredStaticRule evaluatePomRule(final StaticTestSelectionRule rule, final List<String> matchedPaths,
                                            final Map<String, PomChangeReport> reports) {
        Set<PomChangeCategory> firedCategories = EnumSet.noneOf(PomChangeCategory.class);
        List<PomChange> firingChanges = new ArrayList<>();
        List<PomChange> otherChanges = new ArrayList<>();
        for (String path : matchedPaths) {
            for (PomChange change : reports.get(path).getChanges()) {
                if (!change.isInert() && coveredByRule(rule, change.getCategory())) {
                    firedCategories.add(change.getCategory());
                    firingChanges.add(change);
                } else {
                    otherChanges.add(change);
                }
            }
        }
        log.debug("Static test selection rule '{}' matched POM(s) {}; changes: {}",
                rule.getName(), matchedPaths, reports.values());

        if (firedCategories.isEmpty()) {
            log.info("Static test selection rule '{}' matched {} but none of the changes is in {}: {}",
                    rule.getName(), matchedPaths, rule.getPomChanges(),
                    otherChanges.isEmpty() ? "no changes" : summarize(otherChanges));
            return null;
        }
        log.info("Static test selection rule '{}' fired for {} change(s) in {}: {}",
                rule.getName(), firedCategories, matchedPaths, summarize(firingChanges));
        return new FiredStaticRule(rule, matchedPaths.size(), firedCategories);
    }

    /**
     * @param rule a POM-aware rule.
     * @param category a functional change's category.
     * @return {@code true} when one of the rule's configured categories covers the change.
     */
    private boolean coveredByRule(final StaticTestSelectionRule rule, final PomChangeCategory category) {
        for (PomChangeCategory ruleCategory : rule.getPomChanges()) {
            if (ruleCategory.covers(category)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Summarise changes for a log line: the first few, plus a count of the rest.
     *
     * @param changes the changes.
     * @return the summary.
     */
    private String summarize(final List<PomChange> changes) {
        int shown = Math.min(changes.size(), MAX_LOGGED_POM_CHANGES);
        String summary = changes.subList(0, shown).toString();
        return changes.size() > shown ? summary + " and " + (changes.size() - shown) + " more" : summary;
    }

    /**
     * Emit a WARN log line for every rule (or every pattern, in the case of SUITE_NAMES) that
     * resolves to zero test suites in the current tracked-suites snapshot, regardless of
     * whether the rule's file-path regex would match. Helps users spot misconfigured rules
     * early - typoed regexes, patterns that no longer match any tracked suite, etc.
     *
     * <p>For mode {@link StaticTestSelectionRuleMode#RUN_ALL} a single WARN per rule is emitted
     * when there are no tracked suites. For mode
     * {@link StaticTestSelectionRuleMode#SUITE_NAMES} one WARN per pattern is emitted when
     * that specific pattern matches no tracked suite; this lets a rule with a mix of working
     * and broken patterns still surface the broken ones individually.
     *
     * <p>Only invoked once per test-selection run; not called from {@link #resolve(Set, Map, PomChangeAnalyzer)}
     * so the warnings are not duplicated when the rule actually fires.
     *
     * @param testSuitesTracked the tracked test suites keyed by suite name, used to resolve
     *                          each rule's potential suite set.
     */
    public void warnOnEmptyRules(final Map<String, TestSuiteTracker> testSuitesTracked) {
        for (StaticTestSelectionRule rule : config.getRules()) {
            switch (rule.getMode()) {
                case RUN_ALL:
                    warnIfRunAllRuleResolvesEmpty(rule, testSuitesTracked);
                    break;
                case SUITE_NAMES:
                    warnPerPatternThatMatchesNothing(rule, testSuitesTracked);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Emit a WARN for a {@link StaticTestSelectionRuleMode#RUN_ALL} rule when there are no
     * tracked test suites - the rule could fire but would force-run nothing.
     *
     * @param rule the RUN_ALL rule.
     * @param testSuitesTracked the tracked test suites keyed by suite name.
     */
    private void warnIfRunAllRuleResolvesEmpty(final StaticTestSelectionRule rule,
                                               final Map<String, TestSuiteTracker> testSuitesTracked) {
        if (testSuitesTracked == null || testSuitesTracked.isEmpty()) {
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
     * @param testSuitesTracked the tracked test suites keyed by suite name.
     */
    private void warnPerPatternThatMatchesNothing(final StaticTestSelectionRule rule,
                                                  final Map<String, TestSuiteTracker> testSuitesTracked) {
        SuiteNameIndex index = getSuiteNameIndex(testSuitesTracked);
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
     * Collect the changed file paths that match the rule's file-path regex via
     * {@link java.util.regex.Matcher#find()} (substring match). This is the file-listing
     * counterpart to {@link #countMatchingPaths}. {@link #evaluateRules} uses it for POM-aware
     * rules, which need the matched POMs, and for plain rules only when DEBUG logging is enabled
     * (backing the per-rule DEBUG line) so the hot read path avoids the extra allocation on
     * high-volume commit ranges.
     *
     * @param rule the rule whose file-path pattern is applied.
     * @param changedPaths the changed file paths to test.
     * @return the matching paths in iteration order; never {@code null}, may be empty.
     */
    List<String> matchingPaths(final StaticTestSelectionRule rule, final Set<String> changedPaths) {
        List<String> matched = new ArrayList<>();
        for (String path : changedPaths) {
            if (rule.getFilePathPattern().matcher(path).find()) {
                matched.add(path);
            }
        }
        return matched;
    }

    /**
     * Resolve a single rule into the set of suite names it would force-run if its file-path
     * regex matched at least one changed path. Delegates to {@link #resolveForcedSelection} -
     * a rule's mode and suite-name patterns resolve identically whether they come from a fired
     * rule here or a drained library forced-selection batch, so the RUN_ALL / SUITE_NAMES
     * resolution logic lives in one place.
     *
     * @param rule the rule to resolve.
     * @param testSuitesTracked the tracked test suites keyed by suite name.
     * @return the rule's potential forced suite set.
     */
    private Set<String> resolveRule(final StaticTestSelectionRule rule,
                                    final Map<String, TestSuiteTracker> testSuitesTracked) {
        return resolveForcedSelection(rule.getMode(), rule.getSuiteNamePatterns(), testSuitesTracked);
    }

    /**
     * Resolve a decided mode plus suite-name patterns - a rule's outcome once it has fired, or a
     * library forced-selection batch's stored mode and patterns - into the set of tracked suite
     * names to force-run. {@code RUN_ALL} returns every tracked suite; {@code SUITE_NAMES}
     * delegates to {@link #resolveSuiteNamePatterns(List, Map)}. Shared by rule evaluation
     * ({@link #resolveRule}) and the library forced-selection drain, so the SUITE_NAMES matching
     * loop is not duplicated between the two callers.
     *
     * @param mode the selection mode.
     * @param suiteNamePatterns the compiled suite-name patterns; ignored for {@code RUN_ALL}.
     * @param tracked the tracked test suites keyed by suite name.
     * @return the forced suite names; never {@code null}, may be empty.
     */
    public Set<String> resolveForcedSelection(final StaticTestSelectionRuleMode mode,
                                              final List<Pattern> suiteNamePatterns,
                                              final Map<String, TestSuiteTracker> tracked) {
        switch (mode) {
            case RUN_ALL:
                return (tracked == null || tracked.isEmpty())
                        ? Collections.emptySet() : new HashSet<>(tracked.keySet());
            case SUITE_NAMES:
                return resolveSuiteNamePatterns(suiteNamePatterns, tracked);
            default:
                return Collections.emptySet();
        }
    }

    /**
     * Match a set of suite-name patterns against the suite name index built from the tracked
     * suites. Each pattern is matched via {@link java.util.regex.Matcher#find()} against both
     * the simple class name and the fully qualified name of every tracked suite; either match
     * counts as a hit and contributes the FQN(s) to the resolved set. Shared by
     * {@link #resolveRule} (the SUITE_NAMES case) and {@link #resolveForcedSelection} so the
     * matching loop and the lazy {@link SuiteNameIndex} cache are not duplicated.
     *
     * <p>Per-pattern empty-resolution warnings for configured rules are emitted by
     * {@link #warnOnEmptyRules(Map)} and not duplicated here.
     *
     * @param suiteNamePatterns the compiled suite-name patterns to match.
     * @param testSuitesTracked the tracked test suites used to lazily build the suite name index.
     * @return the union of FQNs matched by any of the patterns; never {@code null}, may be empty.
     */
    private Set<String> resolveSuiteNamePatterns(final List<Pattern> suiteNamePatterns,
                                                 final Map<String, TestSuiteTracker> testSuitesTracked) {
        SuiteNameIndex index = getSuiteNameIndex(testSuitesTracked);
        Map<String, List<String>> simpleNameToFqns = index.getSimpleNameToFqns();
        Set<String> fqns = index.getFqns();
        if (fqns.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> matched = new HashSet<>();
        for (Pattern pattern : suiteNamePatterns) {
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
     * Build (or return the cached) {@link SuiteNameIndex} for the tracked suites.
     *
     * @param testSuitesTracked the tracked test suites keyed by suite name.
     * @return the lazily-built index.
     */
    private SuiteNameIndex getSuiteNameIndex(final Map<String, TestSuiteTracker> testSuitesTracked) {
        if (suiteNameIndex == null) {
            suiteNameIndex = new SuiteNameIndex(testSuitesTracked);
        }
        return suiteNameIndex;
    }
}
