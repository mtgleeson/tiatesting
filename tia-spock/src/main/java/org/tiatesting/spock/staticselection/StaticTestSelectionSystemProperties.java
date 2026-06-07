package org.tiatesting.spock.staticselection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses and emits the static test selection rule list passed from the Gradle plugin to the
 * forked test JVM via a system property. The Gradle plugin runs in a JVM that has access to
 * the project model (rules declared in the {@code tia} extension) but cannot directly hand a
 * {@link StaticTestSelectionConfig} object to the test JVM — every value crosses the
 * JVM boundary as a string.
 *
 * <p>The forwarded representation is a single property:
 *
 * <pre>{@code
 * tiaStaticTestSelectionRules = nameB64:filePathPatternB64:MODE:suitePatternB64|suitePatternB64,
 *                               nameB64:filePathPatternB64:MODE:...
 * }</pre>
 *
 * <p>Each rule is comma-separated. Within a rule, fields are colon-separated and positional:
 * <ol>
 *   <li>{@code name} - URL-safe Base64 of the rule's optional display name (may be empty).</li>
 *   <li>{@code filePathPattern} - URL-safe Base64 of the file-path regex.</li>
 *   <li>{@code mode} - the {@link StaticTestSelectionRuleMode} enum name, plain text.</li>
 *   <li>{@code suiteNamePatterns} - pipe-separated list of URL-safe Base64 encoded suite-name
 *       regexes (empty when the rule is RUN_ALL).</li>
 * </ol>
 *
 * <p>Why Base64: regex patterns routinely contain {@code ,}, {@code :}, and {@code |}, all of
 * which are used as delimiters here. URL-safe Base64 limits each encoded field to the
 * alphabet {@code [A-Za-z0-9_-]} and {@code =} padding, none of which collide with the
 * delimiters, so the encoded form is unambiguous regardless of what the underlying regex
 * contains.
 */
public final class StaticTestSelectionSystemProperties {

    private static final Logger log = LoggerFactory.getLogger(StaticTestSelectionSystemProperties.class);

    /** System property carrying the encoded rule list. */
    public static final String PROP_STATIC_TEST_SELECTION_RULES = "tiaStaticTestSelectionRules";

    private static final String RULE_DELIM = ",";
    private static final String FIELD_DELIM = ":";
    private static final String SUITE_DELIM = "|";

    private StaticTestSelectionSystemProperties() {}

    /**
     * Read {@link #PROP_STATIC_TEST_SELECTION_RULES} from {@link System} and parse it into a
     * {@link StaticTestSelectionConfig}. Returns {@link StaticTestSelectionConfig#EMPTY} when
     * the property is absent or blank.
     *
     * @return the parsed config, never {@code null}.
     */
    public static StaticTestSelectionConfig fromSystemProperties() {
        return fromValue(System.getProperty(PROP_STATIC_TEST_SELECTION_RULES));
    }

    /**
     * Parse an encoded rule list into a {@link StaticTestSelectionConfig}. Exposed for testing
     * without touching system state.
     *
     * @param encoded the encoded rule list, in the format documented at the class level. May
     *                be {@code null} or blank, in which case an empty config is returned.
     * @return the parsed config; never {@code null}.
     */
    public static StaticTestSelectionConfig fromValue(final String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) {
            return StaticTestSelectionConfig.EMPTY;
        }

        List<StaticTestSelectionRule> rules = new ArrayList<>();
        for (String ruleRaw : encoded.split(RULE_DELIM, -1)) {
            String trimmed = ruleRaw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            StaticTestSelectionRule rule = parseRule(trimmed);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules.isEmpty() ? StaticTestSelectionConfig.EMPTY : new StaticTestSelectionConfig(rules);
    }

    /**
     * Encode a {@link StaticTestSelectionConfig} into the flat-string form consumed by
     * {@link #fromValue(String)}. Inverse of {@link #fromValue(String)} for any well-formed
     * config produced by user code.
     *
     * @param config the config to encode; {@code null} or disabled produces an empty string.
     * @return the encoded rule list.
     */
    public static String format(final StaticTestSelectionConfig config) {
        if (config == null || !config.isEnabled()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean firstRule = true;
        for (StaticTestSelectionRule rule : config.getRules()) {
            if (!firstRule) {
                sb.append(RULE_DELIM);
            }
            firstRule = false;
            appendRule(sb, rule);
        }
        return sb.toString();
    }

    /**
     * Encode a single rule into the colon-separated form and append it to {@code sb}.
     *
     * @param sb the accumulator.
     * @param rule the rule to encode.
     */
    private static void appendRule(final StringBuilder sb, final StaticTestSelectionRule rule) {
        sb.append(encode(rule.getName())).append(FIELD_DELIM)
          .append(encode(rule.getFilePathPattern().pattern())).append(FIELD_DELIM)
          .append(rule.getMode().name()).append(FIELD_DELIM);

        boolean firstSuite = true;
        for (Pattern suitePattern : rule.getSuiteNamePatterns()) {
            if (!firstSuite) {
                sb.append(SUITE_DELIM);
            }
            firstSuite = false;
            sb.append(encode(suitePattern.pattern()));
        }
    }

    /**
     * Decode a single rule from its colon-separated encoded form. Malformed rules are logged
     * and skipped rather than aborting the whole config, so a single bad entry on the system
     * property doesn't disable static selection entirely.
     *
     * @param encodedRule one rule's encoded form.
     * @return the parsed rule, or {@code null} when parsing or construction fails.
     */
    private static StaticTestSelectionRule parseRule(final String encodedRule) {
        String[] fields = encodedRule.split(FIELD_DELIM, -1);
        if (fields.length < 3) {
            log.warn("Static test selection rule '{}' is malformed (expected at least 3 colon-separated fields), skipping.",
                    encodedRule);
            return null;
        }

        String name = decode(fields[0]);
        String filePathPattern = decode(fields[1]);
        StaticTestSelectionRuleMode mode = parseMode(fields[2]);
        if (mode == null) {
            log.warn("Static test selection rule '{}' has unknown mode '{}', skipping.", encodedRule, fields[2]);
            return null;
        }
        List<String> suitePatterns = parseSuitePatterns(fields.length >= 4 ? fields[3] : "");

        try {
            return new StaticTestSelectionRule(name, filePathPattern, mode, suitePatterns);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            log.warn("Static test selection rule '{}' rejected: {}", encodedRule, e.getMessage());
            return null;
        }
    }

    /**
     * Parse the mode field, accepting only the documented enum names (case-insensitive).
     *
     * @param raw the raw mode string from the encoded rule.
     * @return the parsed enum value, or {@code null} when the value is unknown.
     */
    private static StaticTestSelectionRuleMode parseMode(final String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return StaticTestSelectionRuleMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Split the suite-name pattern list field on the pipe delimiter, Base64-decode each entry,
     * and drop any empty results.
     *
     * @param raw the raw suite-name-patterns field; empty when the rule has no suite patterns.
     * @return the decoded list; never {@code null}, may be empty.
     */
    private static List<String> parseSuitePatterns(final String raw) {
        List<String> patterns = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return patterns;
        }
        for (String part : raw.split(Pattern.quote(SUITE_DELIM), -1)) {
            String decoded = decode(part);
            if (decoded != null && !decoded.isEmpty()) {
                patterns.add(decoded);
            }
        }
        return patterns;
    }

    /**
     * URL-safe Base64 encode a string. Treats {@code null} as empty so the encoded output
     * remains well-formed.
     *
     * @param raw the source string.
     * @return the encoded form (or empty string for {@code null}/empty input).
     */
    private static String encode(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * URL-safe Base64 decode. Returns {@code null} when the input is empty or fails to decode.
     *
     * @param raw the encoded string.
     * @return the decoded UTF-8 string, or {@code null} on error.
     */
    private static String decode(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
