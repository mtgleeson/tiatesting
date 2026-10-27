package org.tiatesting.core.vcs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of checking locally changed files for upstream changes the workspace does not have:
 * changes that are in the commit the stored mapping was built at, but not in the version of the
 * file the local edit is based on. A file with such changes has a different line layout in the
 * workspace than the one the stored method line ranges were recorded against, so matching the
 * local diff to tracked methods by line number is unreliable for it.
 *
 * <p>The baseline (the stored mapping commit) may be missing from the local repository, e.g. a
 * Git clone that has not fetched since the mapping was built. The workspace is then almost
 * certainly behind the baseline, but which files changed can't be worked out locally.
 */
public final class MissingUpstreamChanges {

    private static final MissingUpstreamChanges NONE = new MissingUpstreamChanges(true, Collections.emptyMap());
    private static final MissingUpstreamChanges BASELINE_NOT_FOUND =
            new MissingUpstreamChanges(false, Collections.emptyMap());

    private final boolean baselineFound;
    private final Map<String, List<String>> changesByFetchKey;

    /**
     * @param baselineFound whether the stored mapping commit exists in the local repository
     * @param changesByFetchKey the upstream change ids keyed by each affected file's VCS fetch key
     */
    private MissingUpstreamChanges(final boolean baselineFound, final Map<String, List<String>> changesByFetchKey) {
        this.baselineFound = baselineFound;
        this.changesByFetchKey = changesByFetchKey;
    }

    /**
     * No locally changed file is missing an upstream change.
     *
     * @return the empty result
     */
    public static MissingUpstreamChanges none() {
        return NONE;
    }

    /**
     * The stored mapping commit is not in the local repository, so the files missing upstream
     * changes can't be identified.
     *
     * @return the baseline-not-found result
     */
    public static MissingUpstreamChanges baselineNotFound() {
        return BASELINE_NOT_FOUND;
    }

    /**
     * The locally changed files that are missing upstream changes.
     *
     * @param changesByFetchKey the upstream change ids (commit ids or changelist numbers, oldest
     *                          first) keyed by each affected file's
     *                          {@link org.tiatesting.core.diff.SourceFileDiffContext#getVcsFetchKey() VCS fetch key};
     *                          an empty map means none
     * @return the result
     */
    public static MissingUpstreamChanges of(final Map<String, List<String>> changesByFetchKey) {
        if (changesByFetchKey.isEmpty()) {
            return NONE;
        }
        return new MissingUpstreamChanges(true, Collections.unmodifiableMap(new LinkedHashMap<>(changesByFetchKey)));
    }

    /**
     * @return {@code false} when the stored mapping commit is not in the local repository, in
     *         which case {@link #getChangesByFetchKey()} is empty because nothing could be checked
     */
    public boolean isBaselineFound() {
        return baselineFound;
    }

    /**
     * @return the upstream change ids keyed by each affected file's VCS fetch key; empty when no
     *         file is missing an upstream change or the baseline was not found
     */
    public Map<String, List<String>> getChangesByFetchKey() {
        return changesByFetchKey;
    }
}
