package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a TIA {@code tiaSourceLibs} CSV ({@code groupId:artifactId,...}) to a CSV of absolute
 * JAR file paths. Auto-detects whether the source project at {@code sourceProjectDir} is a Maven
 * ({@code pom.xml}) or Gradle ({@code build.gradle(.kts)} / {@code settings.gradle(.kts)}) build
 * and delegates to the appropriate backend:
 *
 * <ul>
 *   <li>Maven: loads the pom via {@link ProjectBuilder} and reads {@link MavenProject#getArtifacts()}.</li>
 *   <li>Gradle: connects via the Gradle Tooling API and reads
 *       {@link EclipseProject#getClasspath()} for resolved external dependencies and their
 *       {@link GradleModuleVersion} coordinates.</li>
 * </ul>
 *
 * The source project may be a different Maven/Gradle project from the one running TIA, so library
 * resolution must go through the source project's own build file rather than the test project's
 * injected {@link MavenProject}.
 */
class LibraryJarResolver {

    private enum BuildSystem { MAVEN, GRADLE, UNKNOWN }

    private final ProjectBuilder projectBuilder;
    private final ProjectBuildingRequest baseRequest;
    private final Log log;

    LibraryJarResolver(ProjectBuilder projectBuilder, ProjectBuildingRequest baseRequest, Log log){
        this.projectBuilder = projectBuilder;
        this.baseRequest = baseRequest;
        this.log = log;
    }

    /**
     * Resolve a CSV of {@code groupId:artifactId} coordinates against the source project at
     * {@code sourceProjectDir} and return a CSV of absolute JAR paths. Missing or unfile-backed
     * matches are logged as warnings and skipped.
     *
     * @return the joined CSV, or {@code null} when input is blank, the build system can't be
     *         detected, or nothing resolved.
     */
    String resolveLibraryJarsCsv(String tiaSourceLibsCsv, String sourceProjectDir){
        if (tiaSourceLibsCsv == null || tiaSourceLibsCsv.trim().isEmpty()){
            return null;
        }

        List<String> coordinates = parseCoordinates(tiaSourceLibsCsv);
        if (coordinates.isEmpty()){
            return null;
        }

        File dir = new File(sourceProjectDir);
        BuildSystem buildSystem = detectBuildSystem(dir);
        List<String> resolvedJarPaths;
        switch (buildSystem){
            case MAVEN:
                resolvedJarPaths = resolveMaven(coordinates, dir);
                break;
            case GRADLE:
                resolvedJarPaths = resolveGradle(coordinates, dir);
                break;
            case UNKNOWN:
            default:
                log.warn("Source project at " + dir.getAbsolutePath()
                        + " has no pom.xml or build.gradle(.kts) — skipping tiaSourceLibs resolution.");
                return null;
        }

        if (resolvedJarPaths.isEmpty()){
            return null;
        }
        return String.join(",", dedupe(resolvedJarPaths));
    }

    private List<String> parseCoordinates(String tiaSourceLibsCsv){
        List<String> coordinates = new ArrayList<>();
        for (String raw : tiaSourceLibsCsv.split(",")){
            String coord = raw.trim();
            if (!coord.isEmpty()){
                coordinates.add(coord);
            }
        }
        return coordinates;
    }

    private static Set<String> dedupe(List<String> paths){
        return new LinkedHashSet<>(paths);
    }

    private static BuildSystem detectBuildSystem(File dir){
        if (new File(dir, "pom.xml").isFile()){
            return BuildSystem.MAVEN;
        }
        for (String name : new String[]{"build.gradle", "build.gradle.kts",
                                        "settings.gradle", "settings.gradle.kts"}){
            if (new File(dir, name).isFile()){
                return BuildSystem.GRADLE;
            }
        }
        return BuildSystem.UNKNOWN;
    }

    private List<String> resolveMaven(List<String> coordinates, File sourceProjectDir){
        File pomFile = new File(sourceProjectDir, "pom.xml");
        MavenProject sourceProject;
        try {
            ProjectBuildingRequest request = new DefaultProjectBuildingRequest(baseRequest);
            request.setResolveDependencies(true);
            ProjectBuildingResult result = projectBuilder.build(pomFile, request);
            sourceProject = result.getProject();
            log.debug("Resolved source project build config file " + pomFile.getAbsolutePath());
        } catch (ProjectBuildingException e){
            log.warn("Failed to load Maven source project " + pomFile.getAbsolutePath()
                    + " — skipping tiaSourceLibs resolution: " + e.getMessage());
            return Collections.emptyList();
        }

        Set<Artifact> artifacts = sourceProject.getArtifacts();
        List<String> resolvedJarPaths = new ArrayList<>();

        for (String coord : coordinates){
            String[] parts = splitCoordinate(coord);
            if (parts == null){
                continue;
            }
            String groupId = parts[0];
            String artifactId = parts[1];

            Artifact match = findMavenArtifact(artifacts, groupId, artifactId);
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
            log.debug("Resolved tiaSourceLibs coordinate '" + coord + "' to " + path);
            resolvedJarPaths.add(path);
        }
        return resolvedJarPaths;
    }

    private List<String> resolveGradle(List<String> coordinates, File sourceProjectDir){
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(sourceProjectDir);
        try (ProjectConnection connection = connector.connect()) {
            EclipseProject project = connection.getModel(EclipseProject.class);
            log.debug("Resolved Gradle source project at " + sourceProjectDir.getAbsolutePath());
            return matchGradleCoordinates(coordinates, project);
        } catch (Exception e){
            log.warn("Failed to load Gradle source project at " + sourceProjectDir.getAbsolutePath()
                    + " — skipping tiaSourceLibs resolution: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> matchGradleCoordinates(List<String> coordinates, EclipseProject project){
        List<String> resolvedJarPaths = new ArrayList<>();
        for (String coord : coordinates){
            String[] parts = splitCoordinate(coord);
            if (parts == null){
                continue;
            }
            String groupId = parts[0];
            String artifactId = parts[1];

            EclipseExternalDependency match = findGradleDependency(project.getClasspath(), groupId, artifactId);
            if (match == null){
                log.warn("tiaSourceLibs coordinate '" + coord
                        + "' did not match any resolved dependency of Gradle project "
                        + project.getName() + ", skipping.");
                continue;
            }

            File file = match.getFile();
            if (file == null){
                log.warn("tiaSourceLibs coordinate '" + coord
                        + "' matched Gradle dependency but it has no resolved file, skipping.");
                continue;
            }

            String path = file.getAbsolutePath();
            log.debug("Resolved tiaSourceLibs coordinate '" + coord + "' to " + path);
            resolvedJarPaths.add(path);
        }
        return resolvedJarPaths;
    }

    private String[] splitCoordinate(String coord){
        String[] parts = coord.split(":");
        if (parts.length != 2){
            log.warn("Invalid tiaSourceLibs coordinate '" + coord
                    + "' — expected groupId:artifactId, skipping.");
            return null;
        }
        return new String[]{parts[0].trim(), parts[1].trim()};
    }

    private static Artifact findMavenArtifact(Set<Artifact> artifacts, String groupId, String artifactId){
        for (Artifact a : artifacts){
            if (groupId.equals(a.getGroupId()) && artifactId.equals(a.getArtifactId())){
                return a;
            }
        }
        return null;
    }

    private static EclipseExternalDependency findGradleDependency(Iterable<? extends EclipseExternalDependency> classpath,
                                                                  String groupId, String artifactId){
        for (EclipseExternalDependency dep : classpath){
            GradleModuleVersion version = dep.getGradleModuleVersion();
            if (version == null){
                continue;
            }
            if (groupId.equals(version.getGroup()) && artifactId.equals(version.getName())){
                return dep;
            }
        }
        return null;
    }
}
