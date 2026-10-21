package org.tiatesting.core.staticselection.pom;

import java.util.Objects;

/**
 * A single classified difference between two versions of a POM, with a short human-readable
 * description for logging (e.g. {@code dependency org.foo:bar version 1.2 -> 1.3}). Inert changes
 * are kept too, with no category, so a rule that matched a POM but did not fire can explain why.
 */
public final class PomChange {

    private final PomChangeCategory category;
    private final String description;
    private final String plugin;
    private final String profile;

    /**
     * Construct a change.
     *
     * @param category the change's category, or {@code null} for an inert change.
     * @param description the human-readable description of the change.
     * @param plugin the resolved {@code groupId:artifactId} of the plugin the change is in, or {@code null}.
     * @param profile the id of the profile the change is in, or {@code null}.
     */
    private PomChange(final PomChangeCategory category, final String description, final String plugin,
                      final String profile) {
        this.category = category;
        this.description = description;
        this.plugin = plugin;
        this.profile = profile;
    }

    /**
     * Create a change that can affect the behaviour of the code or tests.
     *
     * @param category the change's category; must not be an aggregate such as
     *                 {@link PomChangeCategory#FUNCTIONAL}, which is a rule-side grouping rather
     *                 than a kind of change.
     * @param description the human-readable description of the change.
     * @return the change.
     */
    public static PomChange functional(final PomChangeCategory category, final String description) {
        if (category == null || category.isAggregate()) {
            throw new IllegalArgumentException("A functional POM change needs a concrete category, got " + category);
        }
        return new PomChange(category, description, null, null);
    }

    /**
     * Create a change that cannot affect the behaviour of the code or tests.
     *
     * @param description the human-readable description of the change.
     * @return the change.
     */
    public static PomChange inert(final String description) {
        return new PomChange(null, description, null, null);
    }

    /**
     * @return the change's category, or {@code null} for an inert change.
     */
    public PomChangeCategory getCategory() {
        return category;
    }

    /**
     * @return the human-readable description of the change.
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return the resolved {@code groupId:artifactId} of the plugin the change is in, or
     *         {@code null} when it is not a plugin change. Lets a rule's {@code inertPlugins}
     *         override the plugin's built-in category.
     */
    public String getPlugin() {
        return plugin;
    }

    /**
     * @return the id of the profile the change is in, or {@code null} when it is outside any
     *         profile. Lets a rule's {@code inertProfiles} make the change inert.
     */
    public String getProfile() {
        return profile;
    }

    /**
     * Tag the change with the plugin it is in.
     *
     * @param pluginKey the plugin's resolved {@code groupId:artifactId}.
     * @return a copy carrying the plugin tag.
     */
    PomChange inPlugin(final String pluginKey) {
        return new PomChange(category, description, pluginKey, profile);
    }

    /**
     * Tag the change with the profile it is in. A change already tagged keeps its first profile,
     * the one nearest to it.
     *
     * @param profileId the profile id.
     * @return a copy carrying the profile tag.
     */
    PomChange inProfile(final String profileId) {
        return new PomChange(category, description, plugin, profile != null ? profile : profileId);
    }

    /**
     * Append to the description, keeping the category and the tags; used when a change is
     * attributed to another POM (a child module, an external parent or BOM).
     *
     * @param suffix the text to append.
     * @return the relabelled copy.
     */
    PomChange withDescriptionSuffix(final String suffix) {
        return new PomChange(category, description + suffix, plugin, profile);
    }

    /**
     * @return {@code true} when the change cannot affect the behaviour of the code or tests.
     */
    public boolean isInert() {
        return category == null;
    }

    /**
     * Compare two changes by category and description. The plugin and profile tags are ignored,
     * because the description already names the plugin and profile.
     *
     * @param o the object to compare with.
     * @return {@code true} when {@code o} is a change with the same category and description.
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PomChange that = (PomChange) o;
        return category == that.category && Objects.equals(description, that.description);
    }

    /**
     * Hash the category and description, consistent with {@link #equals(Object)}.
     *
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(category, description);
    }

    /**
     * Describe the change as {@code description (CATEGORY)}, or {@code description (inert)}.
     *
     * @return the description with its category.
     */
    @Override
    public String toString() {
        return description + " (" + (category == null ? "inert" : category.name()) + ")";
    }
}
