package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.slf4j.Logger;

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
public class LibraryJarResolver {

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
            String coord = raw.trim();
            if (!coord.isEmpty()){
                coordinates.add(coord);
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
