package org.tiatesting.core.staticselection.pom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A POM-aware rule's own additions to the built-in inert lists: plugins whose changes are inert
 * ({@code inertPlugins}) and profiles inside which every change is inert ({@code inertProfiles}),
 * for in-house publish or deploy plugins and profiles a test build never runs. Applied per rule
 * to the shared classification through each change's plugin and profile tags, so rules with
 * different overrides never affect each other. See the "Static test selection" chapter in
 * {@code WIKI.md}.
 */
public final class PomInertOverrides {

    /** No overrides. */
    public static final PomInertOverrides NONE = new PomInertOverrides(Collections.<String>emptySet(),
            Collections.<Pattern>emptyList());

    private final Set<String> plugins;
    private final List<Pattern> profiles;

    /**
     * Construct overrides.
     *
     * @param plugins the plugin entries, {@code artifactId} or {@code groupId:artifactId}.
     * @param profiles the compiled profile id patterns.
     */
    private PomInertOverrides(final Set<String> plugins, final List<Pattern> profiles) {
        this.plugins = plugins;
        this.profiles = profiles;
    }

    /**
     * Parse the comma-separated settings, trimming entries and dropping blank ones, and compile
     * the profile patterns once.
     *
     * @param inertPlugins plugin entries, each an {@code artifactId} (any groupId) or a
     *                     {@code groupId:artifactId}; may be {@code null}.
     * @param inertProfiles profile id regexes, each matched against the whole id; may be
     *                      {@code null}. A regex can't contain a comma; use {@code |} alternation.
     * @return the overrides, or {@link #NONE} when both settings are blank.
     * @throws IllegalArgumentException if a profile regex does not compile.
     */
    public static PomInertOverrides parse(final String inertPlugins, final String inertProfiles) {
        Set<String> plugins = new LinkedHashSet<>(split(inertPlugins));
        List<Pattern> profiles = new ArrayList<>();
        for (String regex : split(inertProfiles)) {
            try {
                profiles.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("inertProfiles: invalid regex '" + regex + "': "
                        + e.getDescription(), e);
            }
        }
        if (plugins.isEmpty() && profiles.isEmpty()) {
            return NONE;
        }
        return new PomInertOverrides(Collections.unmodifiableSet(plugins), Collections.unmodifiableList(profiles));
    }

    /**
     * Split a comma-separated setting into trimmed, non-blank entries.
     *
     * @param raw the setting; may be {@code null}.
     * @return the entries, in order.
     */
    private static List<String> split(final String raw) {
        List<String> entries = new ArrayList<>();
        if (raw == null) {
            return entries;
        }
        for (String part : raw.split(",")) {
            String entry = part.trim();
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * @return {@code true} when there are no overrides.
     */
    public boolean isEmpty() {
        return plugins.isEmpty() && profiles.isEmpty();
    }

    /**
     * @return the plugin entries, in configuration order.
     */
    public Set<String> getPlugins() {
        return plugins;
    }

    /**
     * @return the profile id regexes as written, in configuration order.
     */
    public List<String> getProfilePatterns() {
        List<String> patterns = new ArrayList<>(profiles.size());
        for (Pattern profile : profiles) {
            patterns.add(profile.pattern());
        }
        return patterns;
    }

    /**
     * Apply the overrides to one change: a functional change in a listed plugin, or inside a
     * matching profile, becomes inert with the reason appended to its description. Any other
     * change is returned as is.
     *
     * @param change the classified change.
     * @return the change as this rule sees it.
     */
    public PomChange apply(final PomChange change) {
        if (change.isInert() || isEmpty()) {
            return change;
        }
        String reason = reason(change);
        return reason == null ? change : PomChange.inert(change.getDescription() + " (" + reason + ")");
    }

    /**
     * Why an override makes a change inert.
     *
     * @param change a functional change.
     * @return the reason, or {@code null} when no override applies.
     */
    private String reason(final PomChange change) {
        if (change.getPlugin() != null && matchesPlugin(change.getPlugin())) {
            return "plugin listed in inertPlugins";
        }
        String profile = change.getProfile();
        if (profile != null) {
            for (Pattern pattern : profiles) {
                if (pattern.matcher(profile).matches()) {
                    return "in profile " + profile + " matching inertProfiles";
                }
            }
        }
        return null;
    }

    /**
     * Check a plugin key against the entries: a {@code groupId:artifactId} entry must equal the
     * key, a bare {@code artifactId} entry matches the key's artifactId under any groupId.
     *
     * @param pluginKey the plugin's resolved {@code groupId:artifactId}.
     * @return {@code true} when an entry matches.
     */
    private boolean matchesPlugin(final String pluginKey) {
        String artifactId = pluginKey.substring(pluginKey.indexOf(':') + 1);
        for (String entry : plugins) {
            if (entry.indexOf(':') >= 0 ? entry.equals(pluginKey) : entry.equals(artifactId)) {
                return true;
            }
        }
        return false;
    }
}
