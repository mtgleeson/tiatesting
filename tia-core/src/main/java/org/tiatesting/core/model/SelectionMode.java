package org.tiatesting.core.model;

/**
 * How a run's test selection was decided. {@link #SELECTIVE} is ordinary diff-based selection;
 * every other mode runs every test. {@link #SEED} is derived (no stored mapping yet), while
 * {@link #SELECT_ALL} and {@link #RESEED} are asked for by the {@code tiaSelectAllTests} and
 * {@code tiaReseed} flags. See the "Forced runs and re-seed" chapter in {@code WIKI.md}.
 */
public enum SelectionMode {
    /** Ordinary diff-based selection. */
    SELECTIVE("Selective"),
    /** No stored mapping exists yet, so every test runs and the mapping is recorded. */
    SEED("Seed"),
    /** {@code tiaSelectAllTests}: selection overridden, every test runs. */
    SELECT_ALL("All tests (forced)"),
    /** {@code tiaReseed}: every test runs and the seal rebuilds the mapping from scratch. */
    RESEED("Re-seed");

    private final String label;

    SelectionMode(String label) {
        this.label = label;
    }

    /** @return the human-readable name reports and logs print for this mode */
    public String getLabel() {
        return label;
    }

    /** @return true when the mode runs every test - every mode except {@link #SELECTIVE} */
    public boolean isFullRun() {
        return this != SELECTIVE;
    }

    /** @return true when a runtime flag, rather than the absence of a mapping, forced the full run */
    public boolean isForced() {
        return this == SELECT_ALL || this == RESEED;
    }

    /**
     * Map the two runtime flags to a mode. {@code tiaReseed} implies {@code tiaSelectAllTests}, so
     * it wins when both are set.
     *
     * @param selectAllTests the {@code tiaSelectAllTests} flag
     * @param reseed the {@code tiaReseed} flag
     * @return {@link #RESEED}, {@link #SELECT_ALL} or {@link #SELECTIVE}
     */
    public static SelectionMode fromFlags(boolean selectAllTests, boolean reseed) {
        if (reseed) {
            return RESEED;
        }
        return selectAllTests ? SELECT_ALL : SELECTIVE;
    }

    /**
     * Refuse a re-seed on a build that does not own mapping updates. A re-seed exists to rebuild
     * the stored mapping, so a run that writes no mapping cannot perform one; failing fast beats a
     * build that silently runs everything and rebuilds nothing.
     *
     * @param updateDBMapping whether the build owns mapping updates
     * @throws IllegalStateException when this is {@link #RESEED} and {@code updateDBMapping} is false
     */
    public void requireMappingOwner(boolean updateDBMapping) {
        if (this == RESEED && !updateDBMapping) {
            throw new IllegalStateException("tiaReseed requires tiaUpdateDBMapping=true: a re-seed "
                    + "rebuilds the stored mapping, so it must run on the build that owns mapping "
                    + "updates.");
        }
    }

    /**
     * Read a mode stored as its enum name. A missing or unrecognised value reads as
     * {@link #SELECTIVE}, the mode every row written before modes were recorded had.
     *
     * @param name the stored name, possibly null
     * @return the matching mode, or {@link #SELECTIVE}
     */
    public static SelectionMode fromStoredName(String name) {
        if (name == null) {
            return SELECTIVE;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return SELECTIVE;
        }
    }
}
