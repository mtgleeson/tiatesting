package org.tiatesting.core.staticselection.pom;

/**
 * Reads POMs that are not in the repository, such as an external parent or an imported BOM, by
 * coordinates. Used to classify what an external parent or BOM version bump actually changes.
 * Implementations must not download anything: a POM that is not available locally is reported as
 * missing, and the bump is then treated as a dependency version change.
 */
public interface ExternalPomSource {

    /**
     * Read a POM by coordinates.
     *
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the exact version.
     * @return the POM text, or {@code null} when it is not available locally.
     */
    String readPom(String groupId, String artifactId, String version);
}
