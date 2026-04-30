package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.slf4j.Logger;
import org.tiatesting.core.library.LibraryMetadataReader;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a TIA {@code sourceLibs} CSV ({@code groupId:artifactId,...}) to a CSV of absolute
 * JAR file paths for a Gradle source project. Covers three sub-cases:
 *
 * <ol>
 *     <li><b>Same project</b>: {@code sourceProjectDir} is blank or equals the current project's
 *         directory — resolved against the current project's own {@code runtimeClasspath}.</li>
 *     <li><b>Sibling subproject</b>: {@code sourceProjectDir} matches the directory of another
 *         project in the same Gradle build — resolved against that subproject's
 *         {@code runtimeClasspath}.</li>
 *     <li><b>External Gradle build</b>: {@code sourceProjectDir} is a separate Gradle project on
 *         disk — resolved via the Gradle Tooling API ({@link EclipseProject} model).</li>
 * </ol>
 *
 * This resolver only supports Gradle source projects. For Maven source projects use
 * {@code tia-maven-plugin} instead.
 */
public class LibraryJarResolver implements LibraryMetadataReader {

    private final Project project;
    private final Logger log;

    public LibraryJarResolver(Project project, Logger log){
        this.project = project;
        this.log = log;
    }

    /**
     * Resolve a CSV of {@code groupId:artifactId} coordinates against the Gradle source project
     * at {@code sourceProjectDir} and return a CSV of absolute JAR paths. Missing or unresolvable
     * coordinates are logged as warnings and skipped.
     *
     * @param sourceLibsCsv CSV of {@code groupId:artifactId} coordinates, one per line.
     * @param sourceProjectDir the directory of the Gradle source project.
     * @return the joined CSV, or {@code null} when the input is blank or nothing resolved.
     */
    public String resolveLibraryJarsCsv(String sourceLibsCsv, String sourceProjectDir){
        if (sourceLibsCsv == null || sourceLibsCsv.trim().isEmpty()){
            return null;
        }

        List<String> coordinates = new ArrayList<>();
        for (String raw : sourceLibsCsv.split(",")){
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

        List<String> resolved = dispatch(coordinates, sourceProjectDir);
        if (resolved.isEmpty()){
            return null;
        }
        return String.join(",", resolved);
    }

    private List<String> dispatch(List<String> coordinates, String sourceProjectDir){
        String currentDir = project.getProjectDir().getAbsolutePath();

        if (sourceProjectDir == null || sourceProjectDir.trim().isEmpty()
                || sameDir(sourceProjectDir, currentDir)){
            log.debug("Resolving sourceLibs against current project {}", project.getPath());
            return resolveFromGradleProject(project, coordinates);
        }

        Project sibling = findSiblingProject(sourceProjectDir);
        if (sibling != null){
            log.debug("Resolving sourceLibs against sibling subproject {}", sibling.getPath());
            return resolveFromGradleProject(sibling, coordinates);
        }

        File externalDir = new File(sourceProjectDir);
        if (!externalDir.isDirectory()){
            log.warn("sourceProjectDir '" + sourceProjectDir
                    + "' does not exist or is not a directory — skipping sourceLibs resolution.");
            return Collections.emptyList();
        }
        log.debug("Resolving sourceLibs against external Gradle build at {}", externalDir.getAbsolutePath());
        return resolveFromExternalGradleBuild(externalDir, coordinates);
    }

    private Project findSiblingProject(String sourceProjectDir){
        for (Project p : project.getRootProject().getAllprojects()){
            if (sameDir(sourceProjectDir, p.getProjectDir().getAbsolutePath())){
                return p;
            }
        }
        return null;
    }

    private static boolean sameDir(String a, String b){
        return canonical(new File(a)).equals(canonical(new File(b)));
    }

    private static File canonical(File f){
        try {
            return f.getCanonicalFile();
        } catch (IOException e){
            return f.getAbsoluteFile();
        }
    }

    private static String canonicalPath(File f){
        return canonical(f).getAbsolutePath();
    }

    /**
     * A directory passes for "Gradle project root" if it contains any of the standard build/settings
     * files. Used to detect the common misconfiguration where {@code projectDir} points at a source
     * directory rather than the library's project root.
     */
    static boolean looksLikeGradleProjectRoot(File dir){
        return new File(dir, "build.gradle").isFile()
                || new File(dir, "build.gradle.kts").isFile()
                || new File(dir, "settings.gradle").isFile()
                || new File(dir, "settings.gradle.kts").isFile();
    }

    private List<String> resolveFromGradleProject(Project gradleProject, List<String> coordinates){
        Configuration conf = gradleProject.getConfigurations().findByName("runtimeClasspath");
        if (conf == null){
            log.warn("No runtimeClasspath configuration on " + gradleProject.getPath()
                    + " — skipping sourceLibs resolution.");
            return Collections.emptyList();
        }

        Set<ResolvedArtifact> artifacts = conf.getResolvedConfiguration().getResolvedArtifacts();
        List<String> paths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String coord : coordinates){
            String[] parts = coord.split(":");
            if (parts.length != 2){
                log.warn("Invalid sourceLibs coordinate '" + coord
                        + "' — expected groupId:artifactId, skipping.");
                continue;
            }
            String group = parts[0].trim();
            String name = parts[1].trim();

            ResolvedArtifact match = null;
            for (ResolvedArtifact a : artifacts){
                ModuleVersionIdentifier id = a.getModuleVersion().getId();
                if (group.equals(id.getGroup()) && name.equals(id.getName())){
                    match = a;
                    break;
                }
            }

            if (match == null){
                log.warn("sourceLibs coordinate '" + coord
                        + "' did not match any resolved dependency of " + gradleProject.getPath()
                        + " runtimeClasspath, skipping.");
                continue;
            }

            File file = match.getFile();
            if (file == null){
                log.warn("sourceLibs coordinate '" + coord
                        + "' matched artifact but it has no resolved file, skipping.");
                continue;
            }

            String path = file.getAbsolutePath();
            if (seen.add(path)){
                log.debug("Resolved sourceLibs coordinate '{}' to {}", coord, path);
                paths.add(path);
            }
        }
        return paths;
    }

    private List<String> resolveFromExternalGradleBuild(File sourceProjectDir, List<String> coordinates){
        log.warn("Resolving sourceLibs against external Gradle build at " + sourceProjectDir.getAbsolutePath());
        try (ProjectConnection conn = GradleConnector.newConnector()
                .forProjectDirectory(sourceProjectDir)
                .connect()) {
            EclipseProject eclipse = conn.getModel(EclipseProject.class);
            return matchExternalClasspath(eclipse, coordinates, sourceProjectDir);
        } catch (Exception e){
            log.warn("Failed to load Gradle source project at " + sourceProjectDir.getAbsolutePath()
                    + " — skipping sourceLibs resolution: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
        List<LibraryBuildMetadata> result = new ArrayList<>();

        Project sibling = findSiblingProject(libraryProjectDir);
        if (sibling != null) {
            return readMetadataFromGradleProject(sibling, coordinates);
        }

        File libDir = new File(libraryProjectDir);
        if (!libDir.isDirectory()) {
            log.warn("Library project directory '" + libraryProjectDir + "' does not exist.");
            return result;
        }
        if (!looksLikeGradleProjectRoot(libDir)) {
            log.warn("Library project directory '" + libraryProjectDir + "' has no build.gradle / "
                    + "build.gradle.kts / settings.gradle — expected the library's project root, "
                    + "not a source directory. Skipping library build metadata read.");
            return result;
        }

        return readMetadataFromExternalGradleBuild(libDir, coordinates);
    }

    /**
     * Read library build metadata from a Gradle project within the same build.
     */
    private List<LibraryBuildMetadata> readMetadataFromGradleProject(Project gradleProject, List<String> coordinates) {
        List<LibraryBuildMetadata> result = new ArrayList<>();
        String group = String.valueOf(gradleProject.getGroup());
        String name = gradleProject.getName();
        String version = String.valueOf(gradleProject.getVersion());

        for (String coord : coordinates) {
            String[] parts = coord.split(":");
            if (parts.length == 2 && parts[0].trim().equals(group) && parts[1].trim().equals(name)) {
                result.add(new LibraryBuildMetadata(coord, version));
            }
        }

        return result;
    }

    /**
     * Read library build metadata from an external Gradle build via the Tooling API.
     * Uses the {@link EclipseProject} model's classpath entries to find matching coordinates.
     */
    private List<LibraryBuildMetadata> readMetadataFromExternalGradleBuild(File libraryProjectDir,
                                                                           List<String> coordinates) {
        List<LibraryBuildMetadata> result = new ArrayList<>();

        try (ProjectConnection conn = GradleConnector.newConnector()
                .forProjectDirectory(libraryProjectDir)
                .connect()) {
            EclipseProject eclipse = conn.getModel(EclipseProject.class);
            for (EclipseExternalDependency dep : eclipse.getClasspath()) {
                GradleModuleVersion v = dep.getGradleModuleVersion();
                if (v == null) continue;
                String ga = v.getGroup() + ":" + v.getName();
                for (String coord : coordinates) {
                    if (coord.trim().equals(ga)) {
                        result.add(new LibraryBuildMetadata(coord, v.getVersion()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read library build metadata from " + libraryProjectDir.getAbsolutePath()
                    + ": " + e.getMessage());
        }

        return result;
    }

    @Override
    public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir,
                                                                              List<String> coordinates) {
        List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
        String currentDir = project.getProjectDir().getAbsolutePath();

        if (sourceProjectDir == null || sourceProjectDir.trim().isEmpty()
                || sameDir(sourceProjectDir, currentDir)) {
            return resolveFromGradleProjectForLibraryMetadata(project, coordinates);
        }

        Project sibling = findSiblingProject(sourceProjectDir);
        if (sibling != null) {
            return resolveFromGradleProjectForLibraryMetadata(sibling, coordinates);
        }

        File externalDir = new File(sourceProjectDir);
        if (!externalDir.isDirectory()) {
            log.warn("sourceProjectDir '" + sourceProjectDir + "' does not exist — skipping.");
            return result;
        }

        return resolveFromExternalBuildForLibraryMetadata(externalDir, coordinates);
    }

    /**
     * Resolve library metadata from a Gradle project's resolved artifacts.
     */
    private List<ResolvedSourceProjectLibrary> resolveFromGradleProjectForLibraryMetadata(
            Project gradleProject, List<String> coordinates) {
        List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
        Configuration conf = gradleProject.getConfigurations().findByName("runtimeClasspath");
        if (conf == null) {
            return result;
        }

        Set<ResolvedArtifact> artifacts = conf.getResolvedConfiguration().getResolvedArtifacts();

        for (String coord : coordinates) {
            String[] parts = coord.split(":");
            if (parts.length != 2) continue;
            String group = parts[0].trim();
            String name = parts[1].trim();

            for (ResolvedArtifact a : artifacts) {
                ModuleVersionIdentifier id = a.getModuleVersion().getId();
                if (group.equals(id.getGroup()) && name.equals(id.getName())) {
                    File file = a.getFile();
                    String jarPath = file != null ? file.getAbsolutePath() : null;
                    result.add(new ResolvedSourceProjectLibrary(coord, id.getVersion(), jarPath));
                    break;
                }
            }
        }

        return result;
    }

    /**
     * Resolve library metadata from an external Gradle build via the Tooling API.
     */
    private List<ResolvedSourceProjectLibrary> resolveFromExternalBuildForLibraryMetadata(
            File sourceProjectDir, List<String> coordinates) {
        List<ResolvedSourceProjectLibrary> result = new ArrayList<>();

        try (ProjectConnection conn = GradleConnector.newConnector()
                .forProjectDirectory(sourceProjectDir)
                .connect()) {
            EclipseProject eclipse = conn.getModel(EclipseProject.class);

            for (String coord : coordinates) {
                String[] parts = coord.split(":");
                if (parts.length != 2) continue;
                String group = parts[0].trim();
                String name = parts[1].trim();

                for (EclipseExternalDependency dep : eclipse.getClasspath()) {
                    GradleModuleVersion v = dep.getGradleModuleVersion();
                    if (v != null && group.equals(v.getGroup()) && name.equals(v.getName())) {
                        File file = dep.getFile();
                        String jarPath = file != null ? file.getAbsolutePath() : null;
                        result.add(new ResolvedSourceProjectLibrary(coord, v.getVersion(), jarPath));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve libraries from external Gradle build at "
                    + sourceProjectDir.getAbsolutePath() + ": " + e.getMessage());
        }

        return result;
    }

    @Override
    public List<String> readSourceDirectories(String libraryProjectDir) {
        Project sibling = findSiblingProject(libraryProjectDir);
        if (sibling != null) {
            return readSourceDirsFromGradleProject(sibling);
        }

        File libDir = new File(libraryProjectDir);
        if (!libDir.isDirectory()) {
            log.warn("Library project directory '{}' does not exist — cannot read source directories.",
                    libraryProjectDir);
            return Collections.emptyList();
        }
        if (!looksLikeGradleProjectRoot(libDir)) {
            log.warn("Library project directory '{}' has no build.gradle / build.gradle.kts / "
                    + "settings.gradle — expected the library's project root, not a source directory. "
                    + "Skipping source directory read.", libraryProjectDir);
            return Collections.emptyList();
        }

        return readSourceDirsFromExternalGradleBuild(libDir);
    }

    /**
     * Read source directories for a sibling subproject. Uses {@code main.getAllJava().getSrcDirs()}
     * which returns Java + jointly-compiled languages (Groovy, Scala) and naturally excludes
     * resource directories and test source sets. Kotlin sources are not included because the Kotlin
     * compiler doesn't participate in joint compilation; that's a known limitation.
     */
    private List<String> readSourceDirsFromGradleProject(Project gradleProject) {
        List<String> result = new ArrayList<>();
        SourceSetContainer sourceSets = gradleProject.getExtensions()
                .findByType(SourceSetContainer.class);
        if (sourceSets == null) {
            return result;
        }

        SourceSet main = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        if (main != null) {
            for (File srcDir : main.getAllJava().getSrcDirs()) {
                result.add(canonicalPath(srcDir));
            }
        }
        return result;
    }

    /**
     * Read source directories for an external Gradle build via the Tooling API. Filters the
     * Eclipse model's source directories to keep only main-scope code dirs (excludes test sources
     * and resource directories).
     *
     * <p>Filter rules:
     * <ul>
     *     <li>If {@code gradle_used_by_scope} classpath attribute is present, keep entries whose
     *         value contains {@code main}.</li>
     *     <li>When the attribute is absent (older Gradle / non-standard model), fall back to
     *         path convention: keep entries whose path contains {@code /src/main/}.</li>
     *     <li>Always exclude entries whose leaf directory name is {@code resources}.</li>
     * </ul>
     */
    private List<String> readSourceDirsFromExternalGradleBuild(File libraryProjectDir) {
        List<String> result = new ArrayList<>();
        try (ProjectConnection conn = GradleConnector.newConnector()
                .forProjectDirectory(libraryProjectDir)
                .connect()) {
            EclipseProject eclipse = conn.getModel(EclipseProject.class);
            for (EclipseSourceDirectory srcDir : eclipse.getSourceDirectories()) {
                File dir = srcDir.getDirectory();
                if (!isMainCodeSourceDir(srcDir, dir)) {
                    continue;
                }
                result.add(canonicalPath(dir));
            }
        } catch (Exception e) {
            log.warn("Failed to read source directories from external Gradle build at {}: {}",
                    libraryProjectDir.getAbsolutePath(), e.getMessage());
        }
        return result;
    }

    /**
     * Decide whether an Eclipse source directory entry should be included as a tracked library
     * source dir. See {@link #readSourceDirsFromExternalGradleBuild} for the filter rules.
     */
    static boolean isMainCodeSourceDir(EclipseSourceDirectory srcDir, File dir) {
        if (dir == null) {
            return false;
        }
        if ("resources".equals(dir.getName())) {
            return false;
        }

        String scope = readClasspathAttribute(srcDir, "gradle_used_by_scope");
        if (scope != null && !scope.isEmpty()) {
            // Attribute present: trust it. Values can be "main", "test", or "main,test".
            for (String s : scope.split(",")) {
                if ("main".equals(s.trim())) {
                    return true;
                }
            }
            return false;
        }

        // Attribute missing: fall back to path convention.
        String path = dir.getAbsolutePath().replace(File.separatorChar, '/');
        return path.contains("/src/main/");
    }

    private static String readClasspathAttribute(EclipseSourceDirectory srcDir, String name) {
        try {
            for (org.gradle.tooling.model.eclipse.ClasspathAttribute attr : srcDir.getClasspathAttributes()) {
                if (name.equals(attr.getName())) {
                    return attr.getValue();
                }
            }
        } catch (Exception e) {
            // Older Tooling API or model providers may not support classpath attributes.
        }
        return null;
    }

    private List<String> matchExternalClasspath(EclipseProject eclipse, List<String> coordinates,
                                                File sourceProjectDir){
        List<String> paths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String coord : coordinates){
            String[] parts = coord.split(":");
            if (parts.length != 2){
                log.warn("Invalid sourceLibs coordinate '" + coord
                        + "' — expected groupId:artifactId, skipping.");
                continue;
            }
            String group = parts[0].trim();
            String name = parts[1].trim();

            EclipseExternalDependency match = null;
            for (EclipseExternalDependency dep : eclipse.getClasspath()){
                GradleModuleVersion v = dep.getGradleModuleVersion();
                if (v == null){
                    continue;
                }
                if (group.equals(v.getGroup()) && name.equals(v.getName())){
                    match = dep;
                    break;
                }
            }

            if (match == null){
                log.warn("sourceLibs coordinate '" + coord
                        + "' did not match any resolved dependency of Gradle project at "
                        + sourceProjectDir.getAbsolutePath() + ", skipping.");
                continue;
            }

            File file = match.getFile();
            if (file == null){
                log.warn("sourceLibs coordinate '" + coord
                        + "' matched artifact but it has no resolved file, skipping.");
                continue;
            }

            String path = file.getAbsolutePath();
            if (seen.add(path)){
                log.debug("Resolved sourceLibs coordinate '{}' to {}", coord, path);
                paths.add(path);
            }
        }
        return paths;
    }
}
