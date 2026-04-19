package org.tiatesting.core.library;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a library as resolved on the source project's classpath. Captures both the
 * resolved version string and the absolute path to the JAR file so that the caller can
 * compute a content hash when the version is a SNAPSHOT.
 */
public class ResolvedSourceProjectLibrary implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} identity of the library. */
    private String groupArtifact;

    /** Version string as resolved on the source project's classpath. */
    private String resolvedVersion;

    /** Absolute path to the resolved JAR file on disk. */
    private String jarFilePath;

    public ResolvedSourceProjectLibrary() {
    }

    /**
     * Construct a fully populated resolved-library descriptor.
     */
    public ResolvedSourceProjectLibrary(String groupArtifact, String resolvedVersion, String jarFilePath) {
        this.groupArtifact = groupArtifact;
        this.resolvedVersion = resolvedVersion;
        this.jarFilePath = jarFilePath;
    }

    public String getGroupArtifact() {
        return groupArtifact;
    }

    public void setGroupArtifact(String groupArtifact) {
        this.groupArtifact = groupArtifact;
    }

    public String getResolvedVersion() {
        return resolvedVersion;
    }

    public void setResolvedVersion(String resolvedVersion) {
        this.resolvedVersion = resolvedVersion;
    }

    public String getJarFilePath() {
        return jarFilePath;
    }

    public void setJarFilePath(String jarFilePath) {
        this.jarFilePath = jarFilePath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResolvedSourceProjectLibrary that = (ResolvedSourceProjectLibrary) o;
        return Objects.equals(groupArtifact, that.groupArtifact);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupArtifact);
    }

    @Override
    public String toString() {
        return "ResolvedSourceProjectLibrary{groupArtifact='" + groupArtifact
                + "', resolvedVersion='" + resolvedVersion
                + "', jarFilePath='" + jarFilePath + "'}";
    }
}
