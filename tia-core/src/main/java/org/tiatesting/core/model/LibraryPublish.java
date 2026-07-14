package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * One row of a tracked library's publish ledger ({@code tia_library_publish}): a single published
 * build of the library, identified by a per-library monotonically increasing sequence number
 * assigned at publish time. The ledger gives published builds the total order that neither version
 * strings (shared across SNAPSHOT builds) nor jar hashes (opaque) can provide; the drain resolves
 * the artifact the consumer actually holds to its ledger row and drains every pending stamp at or
 * below that row's sequence. See {@code DESIGN-publish-time-stamping.md}.
 */
public class LibraryPublish implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} of the tracked library this publish belongs to. */
    private String groupArtifact;

    /**
     * Per-library monotonically increasing sequence number, assigned {@code max(seq)+1} by the
     * data store when the publish is persisted. {@code 0} means not yet assigned.
     */
    private long publishSeq;

    /** The exact version published (e.g. {@code 0.1.1-SNAPSHOT} or {@code 0.1.1}). */
    private String publishedVersion;

    /** SHA-256 content hash of the published artifact; null when the jar could not be hashed. */
    private String jarHash;

    /** Repo HEAD commit at publish time - provenance for debugging, not used by the drain. */
    private String commitValue;

    /** Publish wall-clock time as UTC epoch milliseconds. */
    private long publishedAt;

    public LibraryPublish() {
    }

    /**
     * Construct a publish ledger row. The sequence number is deliberately not a parameter - it is
     * assigned by the data store when the row is persisted, so callers cannot fabricate ordering.
     *
     * @param groupArtifact {@code groupId:artifactId} of the tracked library.
     * @param publishedVersion the exact version published.
     * @param jarHash SHA-256 content hash of the published artifact, or null when unavailable.
     * @param commitValue repo HEAD commit at publish time.
     * @param publishedAt publish wall-clock time as UTC epoch milliseconds.
     */
    public LibraryPublish(String groupArtifact, String publishedVersion, String jarHash,
                          String commitValue, long publishedAt) {
        this.groupArtifact = groupArtifact;
        this.publishedVersion = publishedVersion;
        this.jarHash = jarHash;
        this.commitValue = commitValue;
        this.publishedAt = publishedAt;
    }

    public String getGroupArtifact() {
        return groupArtifact;
    }

    public void setGroupArtifact(String groupArtifact) {
        this.groupArtifact = groupArtifact;
    }

    public long getPublishSeq() {
        return publishSeq;
    }

    public void setPublishSeq(long publishSeq) {
        this.publishSeq = publishSeq;
    }

    public String getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(String publishedVersion) {
        this.publishedVersion = publishedVersion;
    }

    public String getJarHash() {
        return jarHash;
    }

    public void setJarHash(String jarHash) {
        this.jarHash = jarHash;
    }

    public String getCommitValue() {
        return commitValue;
    }

    public void setCommitValue(String commitValue) {
        this.commitValue = commitValue;
    }

    public long getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(long publishedAt) {
        this.publishedAt = publishedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LibraryPublish that = (LibraryPublish) o;
        return publishSeq == that.publishSeq && Objects.equals(groupArtifact, that.groupArtifact);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupArtifact, publishSeq);
    }

    @Override
    public String toString() {
        return "LibraryPublish{groupArtifact='" + groupArtifact
                + "', publishSeq=" + publishSeq
                + ", publishedVersion='" + publishedVersion
                + "', jarHash='" + (jarHash != null && jarHash.length() > 12 ? jarHash.substring(0, 12) + "..." : jarHash)
                + "', commitValue='" + commitValue + "'}";
    }
}
