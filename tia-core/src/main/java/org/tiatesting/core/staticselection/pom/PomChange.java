package org.tiatesting.core.staticselection.pom;

import java.util.Objects;

/**
 * A single classified difference between two versions of a POM, with a short human-readable
 * description for logging (e.g. {@code dependency org.foo:bar version 1.2 -> 1.3}). Inert changes
 * are kept too, with no category, so a rule that matched a POM but did not fire can explain why.
 * A change inherited from an external parent or imported BOM also records that POM as its
 * {@link #getOrigin() origin}, so a log line can list changes shared by several POMs once.
 */
public final class PomChange {

    private final PomChangeCategory category;
    private final String description;
    private final String origin;

    /**
     * Construct a change.
     *
     * @param category the change's category, or {@code null} for an inert change.
     * @param description the human-readable description of the change.
     * @param origin the external parent or BOM the change was inherited from, or {@code null}.
     */
    private PomChange(final PomChangeCategory category, final String description, final String origin) {
        this.category = category;
        this.description = description;
        this.origin = origin;
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
        return new PomChange(category, description, null);
    }

    /**
     * Create a change that cannot affect the behaviour of the code or tests.
     *
     * @param description the human-readable description of the change.
     * @return the change.
     */
    public static PomChange inert(final String description) {
        return new PomChange(null, description, null);
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
     * @return the external parent or imported BOM the change was inherited from, as
     *         {@code parent <groupId>:<artifactId>:<version>} or {@code BOM <groupId>:<artifactId>:<version>};
     *         {@code null} for a change made in the POM itself. A change inherited through a chain
     *         of parents names the one the POM declares.
     */
    public String getOrigin() {
        return origin;
    }

    /**
     * Append to the description, keeping the category and origin; used when a change is
     * attributed to a child module.
     *
     * @param suffix the text to append.
     * @return the relabelled copy.
     */
    PomChange withDescriptionSuffix(final String suffix) {
        return new PomChange(category, description + suffix, origin);
    }

    /**
     * Attribute the change to the external parent or imported BOM it was inherited from: append
     * {@code " (in <origin>)"} to the description and record {@code origin}, replacing any origin
     * from further up the chain, so the change is attributed to the POM the classified POM declares.
     *
     * @param origin the parent or BOM, as {@code parent <gav>} or {@code BOM <gav>}.
     * @return the relabelled copy.
     */
    public PomChange inheritedFrom(final String origin) {
        return new PomChange(category, description + " (in " + origin + ")", origin);
    }

    /**
     * @return {@code true} when the change cannot affect the behaviour of the code or tests.
     */
    public boolean isInert() {
        return category == null;
    }

    /**
     * Compare two changes by category and description. The origin is left out: an inherited
     * change's description already names it.
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
