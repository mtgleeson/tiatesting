package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Metadata read from a library's own build file (pom.xml or build.gradle).
 * Captures the declared version (GAV) at the HEAD of the library's source tree,
 * which may differ from the version currently resolved on the source project's classpath.
 */
public class LibraryBuildMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} identity of the library. */
    private String groupArtifact;

    /** The version declared in the library's own build file at the current HEAD. */
    private String declaredVersion;

    public LibraryBuildMetadata() {
    }

    /**
     * Construct metadata with the library's identity and declared version.
     */
    public LibraryBuildMetadata(String groupArtifact, String declaredVersion) {
        this.groupArtifact = groupArtifact;
        this.declaredVersion = declaredVersion;
    }

    public String getGroupArtifact() {
        return groupArtifact;
    }

    public void setGroupArtifact(String groupArtifact) {
        this.groupArtifact = groupArtifact;
    }

    public String getDeclaredVersion() {
        return declaredVersion;
    }

    public void setDeclaredVersion(String declaredVersion) {
        this.declaredVersion = declaredVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LibraryBuildMetadata that = (LibraryBuildMetadata) o;
        return Objects.equals(groupArtifact, that.groupArtifact)
                && Objects.equals(declaredVersion, that.declaredVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupArtifact, declaredVersion);
    }

    @Override
    public String toString() {
        return "LibraryBuildMetadata{groupArtifact='" + groupArtifact
                + "', declaredVersion='" + declaredVersion + "'}";
    }
}
