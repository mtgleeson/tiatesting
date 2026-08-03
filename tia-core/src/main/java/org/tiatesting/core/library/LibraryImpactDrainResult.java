package org.tiatesting.core.library;

import java.io.Serializable;
import java.util.*;

/**
 * Captures the outcome of draining pending library impacted-method stamps during test selection.
 * Carried from the selector to the post-test-run persistence step (serialized across the Maven
 * plugin-to-fork boundary) so that drained stamp rows can be deleted and each drained library's
 * {@code tia_library.last_applied_seq} / {@code mapping_baseline_commit} advanced after the tests
 * finish. See the drain-rule section of the library publish-time stamping chapter in {@code WIKI.md}.
 */
public class LibraryImpactDrainResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The {@code (groupArtifact, publishSeq)} keys of the batches drained in this selection run.
     * Each identifies the pending rows of one published build, deleted after the test run completes.
     */
    private final List<DrainedBatchKey> drainedBatchKeys;

    /**
     * The {@code (groupArtifact, publishSeq)} keys of the forced-selection batches drained in
     * this selection run. Each identifies the pending forced-selection rows of one published
     * build, deleted after the test run completes - kept as a separate list from
     * {@link #drainedBatchKeys} because the two batch kinds live in separate pending tables.
     */
    private final List<DrainedBatchKey> drainedForcedBatchKeys;

    /**
     * For each library that was drained, the publish sequence of the build the source project
     * resolved - the new {@code last_applied_seq} to record post-test-run.
     */
    private final Map<String, Long> appliedSeqByLibrary;

    public LibraryImpactDrainResult() {
        this.drainedBatchKeys = new ArrayList<>();
        this.drainedForcedBatchKeys = new ArrayList<>();
        this.appliedSeqByLibrary = new LinkedHashMap<>();
    }

    /**
     * Record a drained batch for post-run deletion.
     *
     * @param groupArtifact the library the batch belongs to.
     * @param publishSeq the publish sequence of the drained batch.
     */
    public void addDrainedBatch(String groupArtifact, long publishSeq) {
        drainedBatchKeys.add(new DrainedBatchKey(groupArtifact, publishSeq));
    }

    /**
     * Record a drained forced-selection batch for post-run deletion.
     *
     * @param groupArtifact the library the forced batch belongs to.
     * @param publishSeq the publish sequence of the drained forced batch.
     */
    public void addDrainedForcedBatch(String groupArtifact, long publishSeq) {
        drainedForcedBatchKeys.add(new DrainedBatchKey(groupArtifact, publishSeq));
    }

    /**
     * Record the resolved build's sequence for a drained library - the value
     * {@code last_applied_seq} advances to post-test-run.
     *
     * @param groupArtifact the drained library.
     * @param appliedSeq the publish sequence of the resolved build.
     */
    public void setAppliedSeq(String groupArtifact, long appliedSeq) {
        appliedSeqByLibrary.put(groupArtifact, appliedSeq);
    }

    public List<DrainedBatchKey> getDrainedBatchKeys() {
        return drainedBatchKeys;
    }

    /** @return the drained forced-selection batch keys for post-run deletion. */
    public List<DrainedBatchKey> getDrainedForcedBatchKeys() {
        return drainedForcedBatchKeys;
    }

    public Map<String, Long> getAppliedSeqByLibrary() {
        return appliedSeqByLibrary;
    }

    public boolean hasDrainedBatches() {
        return !drainedBatchKeys.isEmpty() || !drainedForcedBatchKeys.isEmpty();
    }

    /**
     * Identifies a single pending batch that was drained: {@code (groupArtifact, publishSeq)}.
     */
    public static class DrainedBatchKey implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String groupArtifact;
        private final long publishSeq;

        public DrainedBatchKey(String groupArtifact, long publishSeq) {
            this.groupArtifact = groupArtifact;
            this.publishSeq = publishSeq;
        }

        public String getGroupArtifact() {
            return groupArtifact;
        }

        public long getPublishSeq() {
            return publishSeq;
        }

        @Override
        public String toString() {
            return groupArtifact + "@seq" + publishSeq;
        }
    }
}
