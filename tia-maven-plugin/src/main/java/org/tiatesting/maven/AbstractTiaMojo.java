package org.tiatesting.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryVersionPolicy;
import org.tiatesting.core.vcs.VCSReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractTiaMojo extends AbstractMojo {

    /**
     * Maven project.
     */
    @Parameter(property = "project", readonly = true)
    private MavenProject project;

    @Parameter(property = "tiaBuildDir", defaultValue = "${project.build.directory}/tia")
    String tiaBuildDir;

    /**
     * The file path to the root folder of the project being analyzed.
     *
     */
    @Parameter(property = "tiaProjectDir")
    String tiaProjectDir;

    /**
     * The file path for the saved DB containing the previous analysis of the project.
     */
    @Parameter(property = "tiaDBFilePath")
    String tiaDBFilePath;

    /**
     * The source files directories for the project being analyzed.
     */
    @Parameter(property = "tiaSourceFilesDirs")
    String tiaSourceFilesDirs;

    /**
     * Comma-separated list of {@code groupId:artifactId} coordinates identifying in-repo
     * libraries that should additionally be tracked for coverage. TIA resolves the version
     * from the source project's pom and includes the corresponding JAR in JaCoCo analysis.
     * The library source directories should also be listed in {@link #tiaSourceFilesDirs} so
     * VCS diff analysis picks up changes.
     */
    @Parameter(property = "tiaSourceLibs")
    String tiaSourceLibs;

    /**
     * The file path to the root of the source project — the project whose pom declares the
     * dependencies used to resolve {@link #tiaSourceLibs} to JAR files. Defaults to
     * {@link #tiaProjectDir} when not set. Only needed when the project running the tests is
     * different from the source project being tracked.
     */
    @Parameter(property = "tiaSourceProjectDir")
    String tiaSourceProjectDir;

    /**
     * Project-wide library versioning policy. Accepts {@code BUMP_AT_RELEASE} or
     * {@code BUMP_AFTER_RELEASE} (case-insensitive); defaults to {@code BUMP_AFTER_RELEASE}
     * when not specified. See {@code WIKI.md} for the full model.
     */
    @Parameter(property = "tiaLibraryVersionPolicy")
    String tiaLibraryVersionPolicy;

    /**
     * The test files directories for the project being analyzed.
     */
    @Parameter(property = "tiaTestFilesDirs")
    String tiaTestFilesDirs;

    /**
     * Is TIA enabled?
     */
    @Parameter(property = "tiaEnabled")
    boolean tiaEnabled;

    /**
     * Should the mapping data in the TIA DB be updated with this test run?
     */
    @Parameter(property = "tiaUpdateDBMapping")
    boolean tiaUpdateDBMapping;

    /**
     * Should the stats data in the TIA DB be updated with this test run?
     */
    @Parameter(property = "tiaUpdateDBStats")
    boolean tiaUpdateDBStats;

    /**
     * Specifies the default option for whether Tia should analyse local changes when selecting tests.
     */
    @Parameter(property = "tiaCheckLocalChanges")
    boolean tiaCheckLocalChanges;

    /**
     * Specifies the server URI of the VCS system.
     */
    @Parameter(property = "tiaVcsServerUri")
    String tiaVcsServerUri;

    /**
     * Specifies the username for connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsUserName")
    String tiaVcsUserName;

    /**
     * Specifies the password for connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsPassword")
    String tiaVcsPassword;

    /**
     * Specifies the client name used when connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsClientName")
    String tiaVcsClientName;

    /**
     * Maven project builder used to load the source project's pom so its declared dependencies
     * can be resolved when mapping {@code tiaSourceLibs} coordinates to JAR files.
     */
    @Component
    protected ProjectBuilder projectBuilder;

    /**
     * Current Maven session, used to seed the {@link org.apache.maven.project.ProjectBuildingRequest}
     * passed to {@link #projectBuilder} with the active repositories and settings.
     */
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    public MavenProject getProject(){
        return project;
    }

    public String getTiaBuildDir() {
        return tiaBuildDir;
    }

    public String getTiaProjectDir(){
        return tiaProjectDir;
    }

    public String getTiaDBFilePath(){
        return tiaDBFilePath;
    }

    public String getTiaSourceFilesDirs() {
        return tiaSourceFilesDirs;
    }

    public String getTiaSourceLibs() {
        return tiaSourceLibs;
    }

    /**
     * @return the configured source-project root, or {@link #getTiaProjectDir()} when blank.
     */
    public String getTiaSourceProjectDir() {
        if (tiaSourceProjectDir == null || tiaSourceProjectDir.trim().isEmpty()){
            return getTiaProjectDir();
        }
        return tiaSourceProjectDir;
    }

    public String getTiaLibraryVersionPolicy() {
        return tiaLibraryVersionPolicy;
    }

    public String getTiaTestFilesDirs() {
        return tiaTestFilesDirs;
    }

    public boolean isTiaEnabled() {
        return tiaEnabled;
    }

    public boolean isTiaUpdateDBMapping() {
        return tiaUpdateDBMapping;
    }

    public boolean isTiaUpdateDBStats() {
        return tiaUpdateDBStats;
    }

    public boolean isTiaCheckLocalChanges() {
        return tiaCheckLocalChanges;
    }

    public String getTiaVcsServerUri() {
        return tiaVcsServerUri;
    }

    public String getTiaVcsUserName() {
        return tiaVcsUserName;
    }

    public String getTiaVcsPassword() {
        return tiaVcsPassword;
    }

    public String getTiaVcsClientName() {
        return tiaVcsClientName;
    }

    public abstract VCSReader getVCSReader();

    /**
     * Build the library impact analysis configuration from the Maven plugin parameters.
     * Coordinates in {@link #tiaSourceLibs} should be in the format
     * {@code groupId:artifactId} or {@code groupId:artifactId:projectDir}.
     */
    protected LibraryImpactAnalysisConfig buildLibraryImpactAnalysisConfig() {
        String libs = getTiaSourceLibs();
        LibraryVersionPolicy policy = parseLibraryVersionPolicy(getTiaLibraryVersionPolicy());
        if (libs == null || libs.trim().isEmpty()) {
            return new LibraryImpactAnalysisConfig(null, null, null, null, policy);
        }

        List<String> coordinates = new ArrayList<>();
        Map<String, String> libraryProjectDirs = new HashMap<>();
        for (String raw : libs.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] segments = entry.split(":");
            if (segments.length == 3) {
                String coord = segments[0].trim() + ":" + segments[1].trim();
                coordinates.add(coord);
                libraryProjectDirs.put(coord, segments[2].trim());
            } else if (segments.length == 2) {
                coordinates.add(entry);
            } else {
                getLog().warn("Invalid tiaSourceLibs entry '" + entry
                        + "' — expected groupId:artifactId or groupId:artifactId:projectDir, skipping.");
            }
        }

        LibraryJarResolver reader = new LibraryJarResolver(
                projectBuilder, session.getProjectBuildingRequest(), getLog());

        return new LibraryImpactAnalysisConfig(coordinates, libraryProjectDirs, getTiaSourceProjectDir(), reader, policy);
    }

    private LibraryVersionPolicy parseLibraryVersionPolicy(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LibraryVersionPolicy.BUMP_AFTER_RELEASE;
        }
        try {
            return LibraryVersionPolicy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            getLog().warn("Invalid tiaLibraryVersionPolicy value '" + raw
                    + "' — expected BUMP_AT_RELEASE or BUMP_AFTER_RELEASE. Falling back to BUMP_AFTER_RELEASE.");
            return LibraryVersionPolicy.BUMP_AFTER_RELEASE;
        }
    }
}
