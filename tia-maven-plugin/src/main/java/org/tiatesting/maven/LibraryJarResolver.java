package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.tiatesting.core.library.LibraryMetadataReader;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a TIA {@code tiaSourceLibs} CSV ({@code groupId:artifactId,...}) to a CSV of absolute
 * JAR file paths by loading the source project's {@code pom.xml} via Maven's {@link ProjectBuilder}
 * and matching declared coordinates against the project's resolved artifacts.
 *
 * The source project may be a different Maven project from the one running TIA, so library
 * resolution must go through the source project's own pom rather than the test project's
 * injected {@link MavenProject}. The source project is required to be a Maven build — cross-
 * build-system setups (e.g. Maven test project with a Gradle source project) are not currently
 * supported; the Gradle equivalent lives in {@code tia-gradle}.
 */
class LibraryJarResolver implements LibraryMetadataReader {

    private final ProjectBuilder projectBuilder;
    private final ProjectBuildingRequest baseRequest;
    private final Log log;

    LibraryJarResolver(ProjectBuilder projectBuilder, ProjectBuildingRequest baseRequest, Log log){
        this.projectBuilder = projectBuilder;
        this.baseRequest = baseRequest;
        this.log = log;
    }

    /**
     * Resolve a CSV of {@code groupId:artifactId} coordinates against the Maven source project
     * at {@code sourceProjectDir} and return a CSV of absolute JAR paths. Missing or unfile-backed
     * matches are logged as warnings and skipped.
     *
     * @return the joined CSV, or {@code null} when input is blank or the pom is missing.
     */
    String resolveLibraryJarsCsv(String tiaSourceLibsCsv, String sourceProjectDir){
        if (tiaSourceLibsCsv == null || tiaSourceLibsCsv.trim().isEmpty()){
            return null;
        }

        List<String> coordinates = new ArrayList<>();
        for (String raw : tiaSourceLibsCsv.split(",")){
            String entry = raw.trim();
            if (entry.isEmpty()){
                continue;
            }
            String[] segments = entry.split(":");
            if (segments.length == 3) {
                coordinates.add(segments[0].trim() + ":" + segments[1].trim());
            } else {
                coordinates.add(entry);
            }
        }
        if (coordinates.isEmpty()){
            return null;
        }

        File pomFile = new File(sourceProjectDir, "pom.xml");
        if (!pomFile.isFile()){
            log.warn("Source project pom.xml not found at " + pomFile.getAbsolutePath()
                    + " — skipping tiaSourceLibs resolution. tia-maven-plugin only supports Maven"
                    + " source projects; for Gradle source projects use tia-gradle instead.");
            return null;
        }

        MavenProject sourceProject;
        try {
            ProjectBuildingRequest request = new DefaultProjectBuildingRequest(baseRequest);
            request.setResolveDependencies(true);
            ProjectBuildingResult result = projectBuilder.build(pomFile, request);
            sourceProject = result.getProject();
            log.debug("Resolved source project build config file " + pomFile.getAbsolutePath());
        } catch (ProjectBuildingException e){
            log.warn("Failed to load source project " + pomFile.getAbsolutePath()
                    + " — skipping tiaSourceLibs resolution: " + e.getMessage());
            return null;
        }

        Set<Artifact> artifacts = sourceProject.getArtifacts();
        List<String> resolvedJarPaths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String coord : coordinates){
            String[] parts = coord.split(":");
            if (parts.length < 2 || parts.length > 3){
                log.warn("Invalid tiaSourceLibs coordinate '" + coord
                        + "' — expected groupId:artifactId or groupId:artifactId:projectDir, skipping.");
                continue;
            }
            String groupId = parts[0].trim();
            String artifactId = parts[1].trim();

            Artifact match = findArtifact(artifacts, groupId, artifactId);
            if (match == null){
                log.warn("tiaSourceLibs coordinate '" + coord
                        + "' did not match any resolved dependency of "
                        + sourceProject.getId() + ", skipping.");
                continue;
            }

            File file = match.getFile();
            if (file == null){
                log.warn("tiaSourceLibs coordinate '" + coord
                        + "' matched artifact " + match.getId()
                        + " but it has no resolved file, skipping.");
                continue;
            }

            String path = file.getAbsolutePath();
            if (seen.add(path)){
                log.debug("Resolved tiaSourceLibs coordinate '" + coord + "' to " + path);
                resolvedJarPaths.add(path);
            }
        }

        if (resolvedJarPaths.isEmpty()){
            return null;
        }
        return String.join(",", resolvedJarPaths);
    }

    @Override
    public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
        List<LibraryBuildMetadata> result = new ArrayList<>();

        File pomFile = new File(libraryProjectDir, "pom.xml");
        if (!pomFile.isFile()) {
            log.warn("Library pom.xml not found at " + pomFile.getAbsolutePath()
                    + " — cannot read library build metadata.");
            return result;
        }

        MavenProject libraryProject = loadMavenProject(pomFile);
        if (libraryProject == null) {
            return result;
        }

        for (String coord : coordinates) {
            String[] parts = coord.split(":");
            if (parts.length != 2) {
                continue;
            }
            String groupId = parts[0].trim();
            String artifactId = parts[1].trim();

            if (groupId.equals(libraryProject.getGroupId())
                    && artifactId.equals(libraryProject.getArtifactId())) {
                result.add(new LibraryBuildMetadata(coord, libraryProject.getVersion()));
            }
        }

        return result;
    }

    @Override
    public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir,
                                                                              List<String> coordinates) {
        List<ResolvedSourceProjectLibrary> result = new ArrayList<>();

        File pomFile = new File(sourceProjectDir, "pom.xml");
        if (!pomFile.isFile()) {
            log.warn("Source project pom.xml not found at " + pomFile.getAbsolutePath()
                    + " — cannot resolve libraries in source project.");
            return result;
        }

        MavenProject sourceProject = loadMavenProject(pomFile);
        if (sourceProject == null) {
            return result;
        }

        Set<Artifact> artifacts = sourceProject.getArtifacts();

        for (String coord : coordinates) {
            String[] parts = coord.split(":");
            if (parts.length != 2) {
                continue;
            }
            String groupId = parts[0].trim();
            String artifactId = parts[1].trim();

            Artifact match = findArtifact(artifacts, groupId, artifactId);
            if (match == null) {
                log.warn("Coordinate '" + coord + "' not found on source project classpath, skipping.");
                continue;
            }

            File file = match.getFile();
            String jarPath = file != null ? file.getAbsolutePath() : null;
            result.add(new ResolvedSourceProjectLibrary(coord, match.getVersion(), jarPath));
        }

        return result;
    }

    /**
     * Load a Maven project from a pom file, returning {@code null} on failure.
     */
    private MavenProject loadMavenProject(File pomFile) {
        try {
            ProjectBuildingRequest request = new DefaultProjectBuildingRequest(baseRequest);
            request.setResolveDependencies(true);
            ProjectBuildingResult result = projectBuilder.build(pomFile, request);
            return result.getProject();
        } catch (ProjectBuildingException e) {
            log.warn("Failed to load Maven project at " + pomFile.getAbsolutePath()
                    + ": " + e.getMessage());
            return null;
        }
    }

    private static Artifact findArtifact(Set<Artifact> artifacts, String groupId, String artifactId){
        for (Artifact a : artifacts){
            if (groupId.equals(a.getGroupId()) && artifactId.equals(a.getArtifactId())){
                return a;
            }
        }
        return null;
    }
}