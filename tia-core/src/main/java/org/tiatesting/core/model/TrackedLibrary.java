package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a single library tracked by TIA in the {@code tia_library} table.
 * Each row captures the library's identity (group:artifact), its source directory
 * layout, and the last version/hash observed on the source project's classpath.
 */
public class TrackedLibrary implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} — primary key in {@code tia_library}. */
    private String groupArtifact;

    /** Absolute path to the library's project directory on disk. */
    private String projectDir;

    /** CSV of absolute paths to the library's source directories (e.g. {@code src/main/java}). */
    private String sourceDirsCsv;

    /** Last version of this library observed on the source project's resolved classpath. */
    private String lastSourceProjectVersion;

    /** SHA-256 content hash of the JAR the last time the source project resolved it (SNAPSHOT path). */
    private String lastSourceProjectJarHash;

    /**
     * High-water mark of build-file versions observed for this library. Advances strictly forward
     * whenever a stamp sees a build-file version greater than the stored value; never regresses.
     * Seeded from the library's current build-file version when the library is first onboarded.
     *
     * <p>Under {@code BUMP_AT_RELEASE} this drives the "unknown next version" classification at
     * stamp time. Under {@code BUMP_AFTER_RELEASE} it is maintained symmetrically but does not
     * influence drain decisions. See {@code WIKI.md} for the full model.
     */
    private String lastReleasedLibraryVersion;

    public TrackedLibrary() {
    }

    /**
     * Construct a fully populated tracked-library row.
     *
     * @param groupArtifact {@code groupId:artifactId} — primary key in {@code tia_library}.
     * @param projectDir absolute path to the library's project directory on disk.
     * @param sourceDirsCsv CSV of absolute paths to the library's source directories (e.g. {@code src/main/java}).
     * @param lastSourceProjectVersion last version of this library observed on the source project's resolved classpath.
     * @param lastSourceProjectJarHash SHA-256 content hash of the JAR the last time the source project resolved it (SNAPSHOT path).
     */
    public TrackedLibrary(String groupArtifact, String projectDir, String sourceDirsCsv,
                          String lastSourceProjectVersion, String lastSourceProjectJarHash) {
        this.groupArtifact = groupArtifact;
        this.projectDir = projectDir;
        this.sourceDirsCsv = sourceDirsCsv;
        this.lastSourceProjectVersion = lastSourceProjectVersion;
        this.lastSourceProjectJarHash = lastSourceProjectJarHash;
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

    public String getLastSourceProjectVersion() {
        return lastSourceProjectVersion;
    }

    public void setLastSourceProjectVersion(String lastSourceProjectVersion) {
        this.lastSourceProjectVersion = lastSourceProjectVersion;
    }

    public String getLastSourceProjectJarHash() {
        return lastSourceProjectJarHash;
    }

    public void setLastSourceProjectJarHash(String lastSourceProjectJarHash) {
        this.lastSourceProjectJarHash = lastSourceProjectJarHash;
    }

    public String getLastReleasedLibraryVersion() {
        return lastReleasedLibraryVersion;
    }

    public void setLastReleasedLibraryVersion(String lastReleasedLibraryVersion) {
        this.lastReleasedLibraryVersion = lastReleasedLibraryVersion;
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
                + "', lastSourceProjectVersion='" + lastSourceProjectVersion
                + "', lastReleasedLibraryVersion='" + lastReleasedLibraryVersion + "'}";
    }
}
