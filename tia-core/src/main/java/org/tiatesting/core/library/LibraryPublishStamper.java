package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.diff.diffanalyze.FileImpactAnalyzer;
import org.tiatesting.core.diff.diffanalyze.MethodImpactAnalyzer;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.sourcefile.SourceFilenameUtil;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.util.*;

/**
 * Producer side of publish-time library stamping: invoked from the library module's build when an
 * artifact is published (Maven install/deploy, Gradle publish), it records the published build in
 * the publish ledger and stamps the source methods impacted since the library's mapping baseline -
 * all at the one moment the version and jar content are unambiguous facts. The consumer's drain
 * later resolves the artifact it holds to a ledger row and runs the tests covering every stamp at
 * or below that row's sequence. See the publish-stamp-task section of the library publish-time stamping chapter in {@code WIKI.md}.
 *
 * <p>The impacted-method analysis uses two diffs with distinct jobs. Diff 1 identifies the
 * methods: it diffs the library's source dirs from the library's {@code mapping_baseline_commit}
 * to HEAD - not from the previous publish - so the diff's original-side line coordinates exactly
 * match the tracked method ranges in the mapping and the intersection yields exact method ids. It
 * runs on every publish and is always method-precise (it is cumulative: it re-reports every method
 * that differs from the baseline). Diff 2 is a dedup filter: it diffs from the previous publish's
 * commit to HEAD purely to answer, per file, "did this file change at all since the last publish?"
 * Its hunks are never mapped to methods - the previous-publish coordinates do not align with the
 * baseline-based mapping ranges, so it can only be trusted at file level.
 *
 * <p>The stamp recorded is the method ids from Diff 1 restricted to files that changed since the
 * previous publish (Diff 2). A publish that introduces no new source change (a version-only
 * release, or a re-publish of the same commit) therefore records an empty stamp rather than
 * re-stamping methods already pending from an earlier publish - which would otherwise re-run
 * already-covered tests once a consumer resolves that later build. The filter never drops a
 * genuinely new change (a method is always stamped at the publish whose diff touches its file);
 * it only over-retains an already-stamped method that shares a file with a newly-changed one,
 * costing at worst one extra run of already-green tests. The first publish in each baseline
 * window (the seed, and the first publish after a consumer drain advances the baseline) has its
 * previous publish equal to the baseline, so the filter is a no-op and that publish is fully
 * precise. See the publish-stamp-task and mapping-baseline sections of the library publish-time
 * stamping chapter in {@code WIKI.md}.
 */
public class LibraryPublishStamper {

    private static final Logger log = LoggerFactory.getLogger(LibraryPublishStamper.class);

    private final FileImpactAnalyzer fileImpactAnalyzer = new FileImpactAnalyzer(new MethodImpactAnalyzer());

    /**
     * Record a published library build: write its ledger row (assigning the next per-library
     * sequence) and, in the same transaction, stamp the source methods impacted since the
     * library's mapping baseline.
     *
     * <p>Outcomes:
     * <ul>
     *   <li>{@code SKIPPED_NOT_TRACKED} - the coordinate has no {@code tia_library} row (never
     *       reconciled); nothing is written.</li>
     *   <li>{@code SEEDED} - first publish for the library (no mapping baseline yet): the ledger
     *       row is written, nothing is stamped, and the baseline is seeded to HEAD so stamping
     *       starts from the next publish. Stamping the library's entire history would be wrong.</li>
     *   <li>{@code STAMPED} - the ledger row and the impacted-method stamp are written atomically.
     *       An empty diff still writes the ledger row (the publish happened and must be resolvable
     *       by the consumer's drain lookup) with no stamp rows.</li>
     * </ul>
     *
     * @param dataStore the shared Tia data store (tracked libraries, mapping reads, ledger writes).
     * @param vcsReader the VCS reader for the repository (library and consumer share one repo).
     * @param groupArtifact the {@code groupId:artifactId} of the library being published.
     * @param publishedVersion the exact version being published (e.g. {@code 0.1.1-SNAPSHOT}).
     * @param jarFilePath path to the built artifact to content-hash, or null when unavailable
     *                    (the ledger row is still written, with a null hash).
     * @return the outcome, the assigned publish sequence (0 when skipped) and the stamped method ids.
     */
    public PublishStampResult stampPublish(DataStore dataStore, VCSReader vcsReader, String groupArtifact,
                                           String publishedVersion, String jarFilePath) {
        TrackedLibrary tracked = dataStore.readTrackedLibraries().get(groupArtifact);
        if (tracked == null) {
            log.warn("Library '{}' is not tracked (no tia_library row) - skipping publish stamp.", groupArtifact);
            return new PublishStampResult(PublishStampResult.Outcome.SKIPPED_NOT_TRACKED, 0, Collections.emptySet());
        }

        String jarHash = jarFilePath != null
                ? LibraryJarHasher.computeSha256Hash(new File(jarFilePath)) : null;
        String headCommit = vcsReader.getHeadCommit();
        LibraryPublish publish = new LibraryPublish(groupArtifact, publishedVersion, jarHash,
                headCommit, System.currentTimeMillis());

        if (tracked.getMappingBaselineCommit() == null) {
            long seq = dataStore.persistLibraryPublish(publish, Collections.emptySet(),
                    Collections.<PendingLibraryForcedSelection>emptyList());
            tracked.setMappingBaselineCommit(headCommit);
            dataStore.persistTrackedLibrary(tracked);
            log.info("First publish for library '{}' - ledger seeded at seq {} (version '{}'), baseline commit set to {}; nothing stamped.",
                    groupArtifact, seq, publishedVersion, headCommit);
            return new PublishStampResult(PublishStampResult.Outcome.SEEDED, seq, Collections.emptySet());
        }

        String previousPublishCommit = resolvePreviousPublishCommit(dataStore, groupArtifact, tracked);
        Set<Integer> impactedMethods = findImpactedMethodsSinceBaseline(dataStore, vcsReader, tracked,
                previousPublishCommit);
        // Task 3 replaces this empty list with the forced selections computed from the library's
        // own static test selection rules matched against the same diff.
        long seq = dataStore.persistLibraryPublish(publish, impactedMethods,
                Collections.<PendingLibraryForcedSelection>emptyList());
        log.info("Stamped publish of library '{}' version '{}' at seq {} with {} impacted methods.",
                groupArtifact, publishedVersion, seq, impactedMethods.size());
        return new PublishStampResult(PublishStampResult.Outcome.STAMPED, seq, impactedMethods);
    }

    /**
     * Resolve the commit of the library's most recent publish - the "from" side of the dedup
     * filter's Diff 2. That is the {@code commitValue} of the highest-sequence ledger row for the
     * library; when the ledger is somehow empty (it always holds at least the seed row by the time
     * this runs) it falls back to the mapping baseline, which makes the filter a no-op.
     *
     * @param dataStore the data store to read the publish ledger from.
     * @param groupArtifact the {@code groupId:artifactId} of the library being published.
     * @param tracked the tracked library, used for the baseline fallback.
     * @return the previous publish's commit, or the mapping baseline when no publish exists yet.
     */
    private String resolvePreviousPublishCommit(DataStore dataStore, String groupArtifact, TrackedLibrary tracked) {
        List<LibraryPublish> ledger = dataStore.readLibraryPublishes(groupArtifact);
        if (ledger.isEmpty()) {
            return tracked.getMappingBaselineCommit();
        }
        return ledger.get(ledger.size() - 1).getCommitValue();
    }

    /**
     * Compute the tracked source methods to stamp for this publish. Runs two diffs (see the
     * publish-stamp-task section of the library publish-time stamping chapter in {@code WIKI.md}):
     * Diff 1, from the mapping baseline to HEAD, identifies the impacted methods precisely (its
     * coordinates align with the mapping ranges); Diff 2, from the previous publish's commit to
     * HEAD, is a file-level filter that keeps only the impacted methods whose file actually changed
     * since the previous publish. The result is method-granular but deduped, so a version-only or
     * same-commit re-publish stamps nothing.
     *
     * @param dataStore the data store for the changed-files-to-tracked-methods read.
     * @param vcsReader the VCS reader used to diff and load file content.
     * @param tracked the tracked library whose baseline and source dirs drive the diff.
     * @param previousPublishCommit the commit of the previous publish, driving the Diff 2 filter.
     * @return the impacted tracked method ids; empty when nothing changed since the previous
     *         publish or nothing is tracked.
     */
    private Set<Integer> findImpactedMethodsSinceBaseline(DataStore dataStore, VCSReader vcsReader,
                                                          TrackedLibrary tracked, String previousPublishCommit) {
        List<String> sourceDirs = resolveLibrarySourceDirs(tracked);
        if (sourceDirs.isEmpty()) {
            log.warn("Library '{}' has no source dirs or project dir recorded - cannot diff, stamping nothing.",
                    tracked.getGroupArtifact());
            return Collections.emptySet();
        }

        String baseline = tracked.getMappingBaselineCommit();
        Set<SourceFileDiffContext> diffs = vcsReader.getDiffFiles(baseline, sourceDirs,
                Collections.emptyList(), false);
        List<SourceFileDiffContext> modified = fileImpactAnalyzer
                .groupImpactedTestFiles(diffs, Collections.emptyList())
                .get(FileImpactAnalyzer.SOURCE_FILE_MODIFIED);
        if (modified.isEmpty()) {
            return Collections.emptySet();
        }

        modified = restrictToFilesChangedSincePreviousPublish(vcsReader, tracked, previousPublishCommit,
                modified, sourceDirs);
        if (modified.isEmpty()) {
            return Collections.emptySet();
        }

        Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile =
                loadMethodsTrackedForDiffs(dataStore, modified, sourceDirs);
        List<SourceFileDiffContext> trackedDiffs = filterToTrackedFiles(modified, methodsTrackedByFile, sourceDirs);
        if (trackedDiffs.isEmpty()) {
            return Collections.emptySet();
        }

        vcsReader.loadContentForDiffs(trackedDiffs, baseline, false);
        return fileImpactAnalyzer.getMethodsForFilesChanged(trackedDiffs, methodsTrackedByFile, sourceDirs);
    }

    /**
     * Diff 2, the dedup filter: keep only the baseline-diff modified files that also changed since
     * the previous publish's commit. This is deliberately file-granular - the previous-publish-to-HEAD
     * diff's line coordinates do not align with the baseline-based mapping ranges, so it cannot be
     * used to pick out individual methods, only whole files. It never drops a genuinely new change
     * (a file is always in this diff at the publish that edits it); it only removes files that were
     * already stamped by an earlier publish and untouched since. When the previous publish is the
     * baseline itself (the first publish in a baseline window), the filter is skipped entirely, so
     * that publish stamps the full precise baseline diff.
     *
     * @param vcsReader the VCS reader used to run the since-previous-publish diff.
     * @param tracked the tracked library (used for its group:artifact in logging).
     * @param previousPublishCommit the "from" commit of the filter diff.
     * @param baselineModified the modified files from the baseline diff (Diff 1).
     * @param sourceDirs the library source dirs to restrict the diff to.
     * @return the subset of {@code baselineModified} whose files also changed since the previous
     *         publish; the input unchanged when the previous publish equals the baseline.
     */
    private List<SourceFileDiffContext> restrictToFilesChangedSincePreviousPublish(
            VCSReader vcsReader, TrackedLibrary tracked, String previousPublishCommit,
            List<SourceFileDiffContext> baselineModified, List<String> sourceDirs) {
        String baseline = tracked.getMappingBaselineCommit();
        if (previousPublishCommit == null || previousPublishCommit.equals(baseline)) {
            return baselineModified;
        }

        Set<SourceFileDiffContext> sincePrevious = vcsReader.getDiffFiles(previousPublishCommit, sourceDirs,
                Collections.emptyList(), false);
        Set<String> pathsChangedSincePrevious = new HashSet<>();
        for (SourceFileDiffContext diff : sincePrevious) {
            pathsChangedSincePrevious.add(diff.getNewFilePath());
        }

        List<SourceFileDiffContext> kept = new ArrayList<>();
        for (SourceFileDiffContext diff : baselineModified) {
            if (pathsChangedSincePrevious.contains(diff.getNewFilePath())) {
                kept.add(diff);
            } else {
                log.debug("Library '{}' file {} is impacted since the mapping baseline but unchanged since the "
                                + "previous publish ({}) - already stamped, not re-stamping.",
                        tracked.getGroupArtifact(), diff.getNewFilePath(), previousPublishCommit);
            }
        }
        return kept;
    }

    /**
     * Resolve the directories to diff for a tracked library: its recorded source dirs when
     * present, otherwise its project dir.
     *
     * @param tracked the tracked library.
     * @return the directories to restrict the diff to; empty when neither is recorded.
     */
    private List<String> resolveLibrarySourceDirs(TrackedLibrary tracked) {
        List<String> dirs = new ArrayList<>();
        if (tracked.getSourceDirsCsv() != null && !tracked.getSourceDirsCsv().isEmpty()) {
            for (String dir : tracked.getSourceDirsCsv().split(",")) {
                String trimmed = dir.trim();
                if (!trimmed.isEmpty()) {
                    dirs.add(trimmed);
                }
            }
        } else if (tracked.getProjectDir() != null) {
            dirs.add(tracked.getProjectDir());
        }
        return dirs;
    }

    /**
     * Run the targeted changed-files-to-tracked-methods read for the library's modified diffs:
     * normalize each diff's original file path to its stored mapping key and query the tracked
     * methods (with line ranges) for those files in one call.
     *
     * @param dataStore the data store to query.
     * @param modifiedDiffs the modified source-file diff contexts.
     * @param sourceDirs the library source dirs (used for key normalization).
     * @return the tracked methods for the changed files, keyed by mapping key then method id.
     */
    private Map<String, Map<Integer, MethodImpactTracker>> loadMethodsTrackedForDiffs(
            DataStore dataStore, List<SourceFileDiffContext> modifiedDiffs, List<String> sourceDirs) {
        Set<String> changedFileKeys = new HashSet<>();
        for (SourceFileDiffContext diff : modifiedDiffs) {
            changedFileKeys.add(SourceFilenameUtil.normalizeToMappingKey(diff.getOldFilePath(), sourceDirs));
        }
        return dataStore.getMethodsTrackedForFiles(changedFileKeys);
    }

    /**
     * Filter the modified diffs down to files tracked in the mapping. Untracked changed files
     * have no method ranges to intersect (and no covering tests to select), so they are dropped
     * before the content fetch.
     *
     * @param modifiedDiffs the modified source-file diff contexts.
     * @param methodsTrackedByFile the changed-files-to-tracked-methods result.
     * @param sourceDirs the library source dirs (used for key normalization).
     * @return the subset of diffs whose files are tracked in the mapping.
     */
    private List<SourceFileDiffContext> filterToTrackedFiles(
            List<SourceFileDiffContext> modifiedDiffs,
            Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
            List<String> sourceDirs) {
        List<SourceFileDiffContext> tracked = new ArrayList<>();
        for (SourceFileDiffContext diff : modifiedDiffs) {
            String mappingKey = SourceFilenameUtil.normalizeToMappingKey(diff.getOldFilePath(), sourceDirs);
            if (methodsTrackedByFile.containsKey(mappingKey)) {
                tracked.add(diff);
            } else {
                log.debug("Skipping untracked changed library file: {}", diff.getOldFilePath());
            }
        }
        return tracked;
    }

    /**
     * Result of a publish stamp attempt: what happened, the assigned ledger sequence and the
     * method ids that were stamped.
     */
    public static class PublishStampResult {

        /** What the stamp attempt did - see {@link LibraryPublishStamper#stampPublish}. */
        public enum Outcome {
            /** The coordinate has no tracked-library row; nothing was written. */
            SKIPPED_NOT_TRACKED,
            /** First publish: ledger row written and baseline seeded, nothing stamped. */
            SEEDED,
            /** Ledger row and impacted-method stamp written (stamp possibly empty). */
            STAMPED
        }

        private final Outcome outcome;
        private final long publishSeq;
        private final Set<Integer> stampedMethodIds;

        /**
         * Construct a stamp result.
         *
         * @param outcome what the stamp attempt did.
         * @param publishSeq the assigned ledger sequence, or 0 when nothing was written.
         * @param stampedMethodIds the method ids stamped for this publish; empty when none.
         */
        public PublishStampResult(Outcome outcome, long publishSeq, Set<Integer> stampedMethodIds) {
            this.outcome = outcome;
            this.publishSeq = publishSeq;
            this.stampedMethodIds = stampedMethodIds;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public long getPublishSeq() {
            return publishSeq;
        }

        public Set<Integer> getStampedMethodIds() {
            return stampedMethodIds;
        }
    }
}
