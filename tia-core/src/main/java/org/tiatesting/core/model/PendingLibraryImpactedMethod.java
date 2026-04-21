package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.*;

/**
 * Represents a batch of pending source method IDs for a single library stamp.
 * Each batch is keyed by {@code (groupArtifact, stampVersion)} and holds the set of
 * impacted method IDs that were detected when the library's source changed but the
 * consuming project had not yet picked up the new version.
 */
public class PendingLibraryImpactedMethod implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} of the tracked library. */
    private String groupArtifact;

    /** The library's declared HEAD version at the time the impacted methods were stamped. */
    private String stampVersion;

    /** SHA-256 content hash of the JAR at stamp time (non-null only for SNAPSHOT versions). */
    private String stampJarHash;

    /** The set of source method IDs impacted by the library change at this stamp. */
    private Set<Integer> sourceMethodIds;

    public PendingLibraryImpactedMethod() {
        this.sourceMethodIds = new HashSet<>();
    }

    /**
     * Construct a fully populated pending impacted methods batch.
     *
     * @param groupArtifact {@code groupId:artifactId} of the tracked library.
     * @param stampVersion the library's declared HEAD version at the time the impacted methods were stamped.
     * @param stampJarHash SHA-256 content hash of the JAR at stamp time (non-null only for SNAPSHOT versions).
     * @param sourceMethodIds the set of source method IDs impacted by the library change at this stamp.
     */
    public PendingLibraryImpactedMethod(String groupArtifact, String stampVersion,
                                        String stampJarHash, Set<Integer> sourceMethodIds) {
        this.groupArtifact = groupArtifact;
        this.stampVersion = stampVersion;
        this.stampJarHash = stampJarHash;
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

    public String getStampJarHash() {
        return stampJarHash;
    }

    public void setStampJarHash(String stampJarHash) {
        this.stampJarHash = stampJarHash;
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
        return Objects.equals(groupArtifact, that.groupArtifact)
                && Objects.equals(stampVersion, that.stampVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupArtifact, stampVersion);
    }

    @Override
    public String toString() {
        return "PendingLibraryImpactedMethod{groupArtifact='" + groupArtifact
                + "', stampVersion='" + stampVersion
                + "', methodCount=" + sourceMethodIds.size() + "}";
    }
}
