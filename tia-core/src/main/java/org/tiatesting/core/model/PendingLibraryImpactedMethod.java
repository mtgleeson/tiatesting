package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.*;

/**
 * Represents the pending impacted-method stamp of a single published library build. Each batch is
 * keyed by {@code (groupArtifact, publishSeq)} - the publish-ledger row the changes shipped in -
 * and holds the set of tracked source method ids impacted since the library's mapping baseline.
 * The consumer's drain runs the tests covering every batch at or below the sequence of the build
 * it resolved. See {@code DESIGN-publish-time-stamping.md} section 2.
 */
public class PendingLibraryImpactedMethod implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} of the tracked library. */
    private String groupArtifact;

    /** The version the batch's publish shipped under - display only; the drain keys on {@link #publishSeq}. */
    private String stampVersion;

    /** The publish-ledger sequence of the build this stamp shipped in ({@code tia_library_publish}). */
    private long publishSeq;

    /** The set of source method IDs impacted by the library changes in this publish. */
    private Set<Integer> sourceMethodIds;

    public PendingLibraryImpactedMethod() {
        this.sourceMethodIds = new HashSet<>();
    }

    /**
     * Construct a fully populated pending impacted methods batch.
     *
     * @param groupArtifact {@code groupId:artifactId} of the tracked library.
     * @param stampVersion the version the batch's publish shipped under (display only).
     * @param publishSeq the publish-ledger sequence of the build this stamp shipped in.
     * @param sourceMethodIds the set of source method IDs impacted by the publish's changes.
     */
    public PendingLibraryImpactedMethod(String groupArtifact, String stampVersion,
                                        long publishSeq, Set<Integer> sourceMethodIds) {
        this.groupArtifact = groupArtifact;
        this.stampVersion = stampVersion;
        this.publishSeq = publishSeq;
        this.sourceMethodIds = sourceMethodIds != null ? sourceMethodIds : new HashSet<>();
    }

    public String getGroupArtifact() {
        return groupArtifact;
    }

    public void setGroupArtifact(String groupArtifact) {
        this.groupArtifact = groupArtifact;
    }

    public String getStampVersion() {
        return stampVersion;
    }

    public void setStampVersion(String stampVersion) {
        this.stampVersion = stampVersion;
    }

    public long getPublishSeq() {
        return publishSeq;
    }

    public void setPublishSeq(long publishSeq) {
        this.publishSeq = publishSeq;
    }

    public Set<Integer> getSourceMethodIds() {
        return sourceMethodIds;
    }

    public void setSourceMethodIds(Set<Integer> sourceMethodIds) {
        this.sourceMethodIds = sourceMethodIds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PendingLibraryImpactedMethod that = (PendingLibraryImpactedMethod) o;
        return publishSeq == that.publishSeq
                && Objects.equals(groupArtifact, that.groupArtifact);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupArtifact, publishSeq);
    }

    @Override
    public String toString() {
        return "PendingLibraryImpactedMethod{groupArtifact='" + groupArtifact
                + "', publishSeq=" + publishSeq
                + ", stampVersion='" + stampVersion
                + "', methodCount=" + sourceMethodIds.size() + "}";
    }
}
