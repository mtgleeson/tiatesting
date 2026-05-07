package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.tiatesting.core.library.LibraryMetadataReader;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a TIA {@code tiaSourceLibs} CSV ({@code groupId:artifactId,...}) to a CSV of absolute
 * JAR file paths by loading the source project's {@code pom.xml} via Maven's {@link ProjectBuilder}
 * and matching declared coordinates against the project's resolved artifacts.
 * <br>
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
            ProjectBuildingRequest request = newQuietRequest();
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

        // Library-only metadata (groupId / artifactId / version) doesn't need the transitive
        // dependency graph; skipping resolution avoids the noisy "POM for X is invalid,
        // transitive dependencies will not be available" warning that Maven's resolver emits
        // when a transitive pom in the library's graph fails strict model validation.
        MavenProject libraryProject = loadMavenProject(pomFile, false);
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

        // Source-project resolution genuinely needs the transitive graph because we read
        // sourceProject.getArtifacts() to locate the resolved JAR for each tiaSourceLibs coord.
        MavenProject sourceProject = loadMavenProject(pomFile, true);
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

    @Override
    public List<String> readSourceDirectories(String libraryProjectDir) {
        List<String> result = new ArrayList<>();

        File pomFile = new File(libraryProjectDir, "pom.xml");
        if (!pomFile.isFile()) {
            log.warn("Library pom.xml not found at " + pomFile.getAbsolutePath()
                    + " — cannot read source directories.");
            return result;
        }

        // Source roots come from the project's own model — no transitive resolution needed.
        MavenProject libraryProject = loadMavenProject(pomFile, false);
        if (libraryProject == null) {
            return result;
        }

        List<String> compileRoots = libraryProject.getCompileSourceRoots();
        if (compileRoots != null) {
            for (String root : compileRoots) {
                if (root != null && !root.trim().isEmpty()) {
                    String absolute = Paths.get(root).toAbsolutePath().normalize().toString();
                    result.add(absolute);
                    log.debug("Found source directory " + absolute + " for library " + libraryProject.getId());
                }
            }
        }

        return result;
    }

    /**
     * Load a Maven project from a pom file, returning {@code null} on failure.
     *
     * @param resolveDependencies when {@code true}, Maven walks the transitive dependency graph
     *                            and populates {@link MavenProject#getArtifacts()} — required for
     *                            source-project resolution. When {@code false}, only the project's
     *                            own model is parsed; this is sufficient for reading the library's
     *                            own coordinates / source roots and avoids transitive-pom validation
     *                            warnings that aren't relevant to tia.
     */
    private MavenProject loadMavenProject(File pomFile, boolean resolveDependencies) {
        try {
            ProjectBuildingRequest request = newQuietRequest();
            request.setResolveDependencies(resolveDependencies);
            ProjectBuildingResult result = projectBuilder.build(pomFile, request);
            return result.getProject();
        } catch (ProjectBuildingException e) {
            log.warn("Failed to load Maven project at " + pomFile.getAbsolutePath()
                    + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a {@link ProjectBuildingRequest} that suppresses the noisy
     * {@code "The POM for X is invalid, transitive dependencies (if any) will not be available"}
     * warning emitted by Maven's resolver when a tracked library's POM trips strict descriptor
     * validation while being read as a transitive of the source project.
     *
     * <p>The warning fires from {@code DefaultArtifactDescriptorReader} regardless of any
     * project-level validation level we set on the building request itself, because transitive
     * descriptor reads use the {@link RepositorySystemSession}'s
     * {@link org.eclipse.aether.resolution.ArtifactDescriptorPolicy} rather than the request's.
     * The fix is to clone the session and install a {@link SimpleArtifactDescriptorPolicy} that
     * silently ignores both invalid and missing descriptors. Tia only needs the resolved-artifact
     * set to locate JAR files for {@code tiaSourceLibs} coordinates — transitives with broken
     * POMs aren't actionable for test-impact analysis, so swallowing the warning has no behavioural
     * cost for Tia's use case.
     *
     * @return a per-call request whose repository session ignores invalid/missing descriptors;
     *         falls back to a plain copy of {@link #baseRequest} if no session is present.
     */
    private ProjectBuildingRequest newQuietRequest() {
        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(baseRequest);
        RepositorySystemSession baseSession = baseRequest.getRepositorySession();
        if (baseSession instanceof DefaultRepositorySystemSession) {
            DefaultRepositorySystemSession quiet = new DefaultRepositorySystemSession(baseSession);
            quiet.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(true, true));
            request.setRepositorySession(quiet);
        }
        return request;
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