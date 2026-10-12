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

    /**
     * Construct a change.
     *
     * @param category the change's category, or {@code null} for an inert change.
     * @param description the human-readable description of the change.
     */
    private PomChange(final PomChangeCategory category, final String description) {
        this.category = category;
        this.description = description;
    }

    /**
     * Create a change that can affect the behaviour of the code or tests.
     *
     * @param category the change's category; must not be {@link PomChangeCategory#FUNCTIONAL},
     *                 which is a rule-side grouping rather than a kind of change.
     * @param description the human-readable description of the change.
     * @return the change.
     */
    public static PomChange functional(final PomChangeCategory category, final String description) {
        if (category == null || category == PomChangeCategory.FUNCTIONAL) {
            throw new IllegalArgumentException("A functional POM change needs a concrete category, got " + category);
        }
        return new PomChange(category, description);
    }

    /**
     * Create a change that cannot affect the behaviour of the code or tests.
     *
     * @param description the human-readable description of the change.
     * @return the change.
     */
    public static PomChange inert(final String description) {
        return new PomChange(null, description);
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
     * @return {@code true} when the change cannot affect the behaviour of the code or tests.
     */
    public boolean isInert() {
        return category == null;
    }

    /**
     * Compare two changes by category and description.
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
