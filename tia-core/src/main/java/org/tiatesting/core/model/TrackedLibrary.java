package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a single library tracked by TIA in the {@code tia_library} table.
 * Each row captures the library's identity (group:artifact), its source directory layout, and
 * the consumer-side ledger state: the mapping baseline commit the publish-time stamper diffs
 * from, and the last applied publish sequence used for downgrade warnings and reporting.
 * See the library publish-time stamping chapter in {@code WIKI.md}.
 */
public class TrackedLibrary implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} — primary key in {@code tia_library}. */
    private String groupArtifact;

    /** Absolute path to the library's project directory on disk. */
    private String projectDir;

    /** CSV of absolute paths to the library's source directories (e.g. {@code src/main/java}). */
    private String sourceDirsCsv;

    /**
     * The commit at which this library's tracked method line numbers were last captured (an
     * all-tests run, or a primary run that drained this library's stamps and so re-covered its
     * suites). The publish-time stamp task diffs from this commit so the diff's original-side
     * line coordinates exactly match the mapping's ranges. Null until first captured.
     * See the mapping-baseline section of the library publish-time stamping chapter in {@code WIKI.md}.
     */
    private String mappingBaselineCommit;

    /**
     * Monotonic high-water mark: the {@code publishSeq} of the last published build whose
     * impacted tests the consumer has run. Deliberately not part of the drain predicate (the
     * pending-stamp set is self-describing via delete-on-drain); used only for the downgrade /
     * stale-resolve warning and reporting. Null until the first drain.
     * See the drain-rule section of the library publish-time stamping chapter in {@code WIKI.md}.
     */
    private Long lastAppliedSeq;

    public TrackedLibrary() {
    }

    /**
     * Construct a tracked-library row from its configuration-derived identity. The ledger state
     * fields ({@code mappingBaselineCommit}, {@code lastAppliedSeq}) start null and are advanced
     * by the publish stamper and the post-test-run cleanup respectively.
     *
     * @param groupArtifact {@code groupId:artifactId} — primary key in {@code tia_library}.
     * @param projectDir absolute path to the library's project directory on disk.
     * @param sourceDirsCsv CSV of absolute paths to the library's source directories (e.g. {@code src/main/java}).
     */
    public TrackedLibrary(String groupArtifact, String projectDir, String sourceDirsCsv) {
        this.groupArtifact = groupArtifact;
        this.projectDir = projectDir;
        this.sourceDirsCsv = sourceDirsCsv;
    }

    public String getGroupArtifact() {
        return groupArtifact;
    }

    public void setGroupArtifact(String groupArtifact) {
        this.groupArtifact = groupArtifact;
    }

    public String getProjectDir() {
        return projectDir;
    }

    public void setProjectDir(String projectDir) {
        this.projectDir = projectDir;
    }

    public String getSourceDirsCsv() {
        return sourceDirsCsv;
    }

    public void setSourceDirsCsv(String sourceDirsCsv) {
        this.sourceDirsCsv = sourceDirsCsv;
    }

    public String getMappingBaselineCommit() {
        return mappingBaselineCommit;
    }

    public void setMappingBaselineCommit(String mappingBaselineCommit) {
        this.mappingBaselineCommit = mappingBaselineCommit;
    }

    public Long getLastAppliedSeq() {
        return lastAppliedSeq;
    }

    public void setLastAppliedSeq(Long lastAppliedSeq) {
        this.lastAppliedSeq = lastAppliedSeq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrackedLibrary that = (TrackedLibrary) o;
        return Objects.equals(groupArtifact, that.groupArtifact);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupArtifact);
    }

    @Override
    public String toString() {
        return "TrackedLibrary{groupArtifact='" + groupArtifact
                + "', mappingBaselineCommit='" + mappingBaselineCommit
                + "', lastAppliedSeq=" + lastAppliedSeq + "}";
    }
}
