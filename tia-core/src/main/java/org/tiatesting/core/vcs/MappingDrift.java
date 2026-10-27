package org.tiatesting.core.vcs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of checking locally changed files for drift from the mapping: whether the version of
 * each file the local edit is based on differs from the version at the commit the stored mapping
 * was built at. The stored method line ranges were recorded from the mapped version, so for a
 * drifted file they can describe a different line layout than the one the local diff is taken
 * against, and matching the local diff to tracked methods by line number is unreliable for it.
 *
 * <p>A file drifts in either or both directions:
 * <ul>
 *     <li><b>Behind</b> - the mapped version has changes the workspace version doesn't
 *     ({@link FileDrift#getChangesNotInWorkspace()}).</li>
 *     <li><b>Ahead</b> - the workspace version has changes the mapped version doesn't, e.g. the
 *     developer synced past the mapping commit, or committed their own changes
 *     ({@link FileDrift#getChangesNotInMapping()}).</li>
 * </ul>
 *
 * <p>The baseline (the stored mapping commit) may be missing from the local repository, e.g. a
 * Git clone that has not fetched since the mapping was built. The workspace is then almost
 * certainly behind the baseline, but which files drifted can't be worked out locally.
 */
public final class MappingDrift {

    private static final MappingDrift NONE = new MappingDrift(true, Collections.emptyMap());
    private static final MappingDrift BASELINE_NOT_FOUND = new MappingDrift(false, Collections.emptyMap());

    private final boolean baselineFound;
    private final Map<String, FileDrift> driftByFetchKey;

    /**
     * @param baselineFound whether the stored mapping commit exists in the local repository
     * @param driftByFetchKey the drift of each drifted file, keyed by its VCS fetch key
     */
    private MappingDrift(final boolean baselineFound, final Map<String, FileDrift> driftByFetchKey) {
        this.baselineFound = baselineFound;
        this.driftByFetchKey = driftByFetchKey;
    }

    /**
     * No locally changed file differs from its mapped version.
     *
     * @return the empty result
     */
    public static MappingDrift none() {
        return NONE;
    }

    /**
     * The stored mapping commit is not in the local repository, so the drifted files can't be
     * identified.
     *
     * @return the baseline-not-found result
     */
    public static MappingDrift baselineNotFound() {
        return BASELINE_NOT_FOUND;
    }

    /**
     * The locally changed files that differ from their mapped version.
     *
     * @param driftByFetchKey the drift of each drifted file, keyed by its
     *                        {@link org.tiatesting.core.diff.SourceFileDiffContext#getVcsFetchKey() VCS fetch key};
     *                        an empty map means none
     * @return the result
     */
    public static MappingDrift of(final Map<String, FileDrift> driftByFetchKey) {
        if (driftByFetchKey.isEmpty()) {
            return NONE;
        }
        return new MappingDrift(true, Collections.unmodifiableMap(new LinkedHashMap<>(driftByFetchKey)));
    }

    /**
     * @return {@code false} when the stored mapping commit is not in the local repository, in
     *         which case {@link #getDriftByFetchKey()} is empty because nothing could be checked
     */
    public boolean isBaselineFound() {
        return baselineFound;
    }

    /**
     * @return the drift of each drifted file keyed by its VCS fetch key; empty when no file drifted
     *         or the baseline was not found
     */
    public Map<String, FileDrift> getDriftByFetchKey() {
        return driftByFetchKey;
    }

    /**
     * How one file's workspace version differs from its mapped version, as the change ids (commit
     * ids or changelist numbers, oldest first) that touched the file on each side.
     */
    public static final class FileDrift {

        private final List<String> changesNotInWorkspace;
        private final List<String> changesNotInMapping;

        /**
         * @param changesNotInWorkspace the changes to the file in the mapped version that the
         *                              workspace version doesn't have (behind); may be empty
         * @param changesNotInMapping the changes to the file in the workspace version that the
         *                            mapped version doesn't have (ahead); may be empty
         */
        public FileDrift(final List<String> changesNotInWorkspace, final List<String> changesNotInMapping) {
            this.changesNotInWorkspace = Collections.unmodifiableList(changesNotInWorkspace);
            this.changesNotInMapping = Collections.unmodifiableList(changesNotInMapping);
        }

        /**
         * @return the changes in the mapped version the workspace version doesn't have, oldest first
         */
        public List<String> getChangesNotInWorkspace() {
            return changesNotInWorkspace;
        }

        /**
         * @return the changes in the workspace version the mapped version doesn't have, oldest first
         */
        public List<String> getChangesNotInMapping() {
            return changesNotInMapping;
        }

        /**
         * Compare by both change lists, so tests can assert on expected drift values.
         *
         * @param o the other object
         * @return whether {@code o} is a {@code FileDrift} with the same changes on both sides
         */
        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FileDrift)) {
                return false;
            }
            FileDrift other = (FileDrift) o;
            return changesNotInWorkspace.equals(other.changesNotInWorkspace)
                    && changesNotInMapping.equals(other.changesNotInMapping);
        }

        /**
         * @return a hash of both change lists, consistent with {@link #equals(Object)}
         */
        @Override
        public int hashCode() {
            return 31 * changesNotInWorkspace.hashCode() + changesNotInMapping.hashCode();
        }

        /**
         * @return both change lists, for assertion messages
         */
        @Override
        public String toString() {
            return "FileDrift{notInWorkspace=" + changesNotInWorkspace + ", notInMapping=" + changesNotInMapping + "}";
        }
    }
}
