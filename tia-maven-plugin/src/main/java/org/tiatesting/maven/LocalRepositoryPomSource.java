package org.tiatesting.maven;

import org.apache.maven.plugin.logging.Log;
import org.tiatesting.core.staticselection.pom.ExternalPomSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads external POMs (parents, imported BOMs) from the Maven local repository, using the standard
 * {@code <groupId path>/<artifactId>/<version>/<artifactId>-<version>.pom} layout. Nothing is
 * downloaded: a POM not in the local repository is reported as missing. The POM for the version
 * a build uses is always present (Maven resolved it to build); the previous version may not be on a
 * fresh CI cache, which is why the Maven plugin wraps this in a {@link ResolvingPomSource}.
 */
public class LocalRepositoryPomSource implements ExternalPomSource {

    private final File localRepositoryDir;
    private final Log log;

    /**
     * Construct a source over a local repository directory.
     *
     * @param localRepositoryDir the local repository's base directory (e.g. {@code ~/.m2/repository}).
     * @param log the Maven log, for a debug line per POM not found.
     */
    public LocalRepositoryPomSource(final File localRepositoryDir, final Log log) {
        this.localRepositoryDir = localRepositoryDir;
        this.log = log;
    }

    /**
     * Read a POM from the local repository.
     *
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the exact version.
     * @return the POM text, or {@code null} when the file is not in the local repository or can't be read.
     */
    @Override
    public String readPom(final String groupId, final String artifactId, final String version) {
        File pom = pomFile(groupId, artifactId, version);
        if (!pom.isFile()) {
            log.debug("POM " + groupId + ":" + artifactId + ":" + version + " is not in the local repository ("
                    + pom + ").");
            return null;
        }
        try {
            return new String(Files.readAllBytes(pom.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not read " + pom + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The local repository file for a POM.
     *
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the version.
     * @return the file path; it may not exist.
     */
    File pomFile(final String groupId, final String artifactId, final String version) {
        File versionDir = new File(new File(new File(localRepositoryDir, groupId.replace('.', File.separatorChar)),
                artifactId), version);
        return new File(versionDir, artifactId + "-" + version + ".pom");
    }
}
