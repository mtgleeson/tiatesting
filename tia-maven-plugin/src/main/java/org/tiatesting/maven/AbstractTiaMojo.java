package org.tiatesting.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryVersionPolicy;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;
import org.tiatesting.core.vcs.VCSReader;

import java.util.ArrayList;
import java.util.Collections;
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
     * Used for embedded H2 mode. Ignored when {@link #tiaDBUrl} is set.
     */
    @Parameter(property = "tiaDBFilePath")
    String tiaDBFilePath;

    /**
     * JDBC URL of an H2 database running in server (TCP) mode, e.g.
     * {@code jdbc:h2:tcp://h2host:9092/tiadb}. When set, Tia connects to that server instead of
     * an embedded file-on-disk database and {@link #tiaDBFilePath} is ignored. The URL is used
     * verbatim, so per-branch isolation (if wanted) must be encoded in the URL.
     */
    @Parameter(property = "tiaDBUrl")
    String tiaDBUrl;

    /**
     * Database username for server-mode H2 ({@link #tiaDBUrl}). Defaults to {@code sa} when unset.
     */
    @Parameter(property = "tiaDBUser")
    String tiaDBUser;

    /**
     * Database password for server-mode H2 ({@link #tiaDBUrl}). Defaults to an empty password
     * when unset.
     */
    @Parameter(property = "tiaDBPassword")
    String tiaDBPassword;

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
     * The file path to the root of the source project - the project whose pom declares the
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
     * The compiled class file directories for the project being analyzed, forwarded to the forked
     * test JVM for {@code JacocoClient}. Optional.
     */
    @Parameter(property = "tiaClassFilesDirs")
    String tiaClassFilesDirs;

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
     * Should this run log a row to the {@code tia_test_run_history} table? Defaults to
     * {@code true} - the history log is cheap and only useful when continuously populated.
     */
    @Parameter(property = "tiaUpdateDBTestRunHistory", defaultValue = "true")
    boolean tiaUpdateDBTestRunHistory;

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
     * Static test selection rules. Each rule maps a regex over the repo-relative paths of
     * changed files to a set of test suites that should be force-run regardless of dynamic
     * coverage-based selection. Rules are additive: their selected suites are unioned into
     * the dynamic test selection.
     *
     * <p>Configured as a nested element list in the plugin block, e.g.
     * <pre>{@code
     * <tiaStaticTestSelectionRules>
     *   <tiaStaticTestSelectionRule>
     *     <name>db-migrations</name>
     *     <filePathPattern>src/main/resources/db/migrations/.*\.sql$</filePathPattern>
     *     <mode>SUITE_NAMES</mode>
     *     <suiteNamePatterns>
     *       <suiteNamePattern>.*MigrationIT$</suiteNamePattern>
     *     </suiteNamePatterns>
     *   </tiaStaticTestSelectionRule>
     * </tiaStaticTestSelectionRules>
     * }</pre>
     */
    @Parameter
    List<MavenStaticTestSelectionRule> tiaStaticTestSelectionRules;

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

    /**
     * @return the configured server-mode H2 JDBC URL, or {@code null} for embedded mode
     */
    public String getTiaDBUrl(){
        return tiaDBUrl;
    }

    /**
     * @return the configured server-mode H2 username, or {@code null} to use the default
     */
    public String getTiaDBUser(){
        return tiaDBUser;
    }

    /**
     * @return the configured server-mode H2 password, or {@code null} to use the default
     */
    public String getTiaDBPassword(){
        return tiaDBPassword;
    }

    /**
     * Resolve the H2 connection settings for this mojo. Picks server mode when {@link #tiaDBUrl}
     * is set, otherwise embedded mode keyed on the supplied branch name. Centralising this here
     * keeps every mojo's {@link org.tiatesting.core.persistence.h2.H2DataStore} construction
     * consistent.
     *
     * @param branchSuffix the VCS branch name, used as the embedded-mode file suffix
     * @return the resolved embedded- or server-mode connection settings
     */
    protected H2ConnectionSettings buildH2ConnectionSettings(final String branchSuffix){
        return H2ConnectionSettings.fromConfig(getTiaDBFilePath(), getTiaDBUrl(),
                getTiaDBUser(), getTiaDBPassword(), branchSuffix);
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

    public String getTiaClassFilesDirs() {
        return tiaClassFilesDirs;
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

    /**
     * @return whether this run should log a row to the {@code tia_test_run_history} table
     */
    public boolean isTiaUpdateDBTestRunHistory() {
        return tiaUpdateDBTestRunHistory;
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

    /**
     * @return the configured static test selection rules, or an empty list when not configured.
     */
    public List<MavenStaticTestSelectionRule> getTiaStaticTestSelectionRules() {
        return tiaStaticTestSelectionRules != null ? tiaStaticTestSelectionRules : Collections.emptyList();
    }

    public abstract VCSReader getVCSReader();

    /**
     * Build the library impact analysis configuration from the Maven plugin parameters.
     * Coordinates in {@link #tiaSourceLibs} should be in the format
     * {@code groupId:artifactId} or {@code groupId:artifactId:projectDir}.
     *
     * @return the library impact analysis configuration parsed from the mojo's
     *         {@code tiaSourceLibs}, {@code tiaSourceProjectDir}, and
     *         {@code tiaLibraryVersionPolicy} parameters.
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
                        + "' - expected groupId:artifactId or groupId:artifactId:projectDir, skipping.");
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
                    + "' - expected BUMP_AT_RELEASE or BUMP_AFTER_RELEASE. Falling back to BUMP_AFTER_RELEASE.");
            return LibraryVersionPolicy.BUMP_AFTER_RELEASE;
        }
    }

    /**
     * Build the static test selection configuration from the Maven nested
     * {@code <tiaStaticTestSelectionRules>} block. Validates each entry, parses its mode, and
     * pre-compiles its regex patterns. Returns {@link StaticTestSelectionConfig#EMPTY} when
     * no rules are configured.
     *
     * @return the parsed static test selection config.
     * @throws IllegalArgumentException if any rule is missing required fields, has an unknown
     *                                  mode, or contains an invalid regex.
     */
    protected StaticTestSelectionConfig buildStaticTestSelectionConfig() {
        List<MavenStaticTestSelectionRule> rawRules = getTiaStaticTestSelectionRules();
        if (rawRules.isEmpty()) {
            return StaticTestSelectionConfig.EMPTY;
        }

        List<StaticTestSelectionRule> compiledRules = new ArrayList<>(rawRules.size());
        for (MavenStaticTestSelectionRule raw : rawRules) {
            StaticTestSelectionRuleMode mode = parseStaticTestSelectionRuleMode(raw.getMode(), raw.getFilePathPattern());
            compiledRules.add(new StaticTestSelectionRule(
                    raw.getName(), raw.getFilePathPattern(), mode, raw.getSuiteNamePatterns()));
        }
        return new StaticTestSelectionConfig(compiledRules);
    }

    /**
     * Parse the raw mode string from the Maven config into the core enum. Empty or unknown
     * values produce a clear error rather than a silent default; we'd rather fail the build
     * than mis-route a rule.
     *
     * @param raw the raw mode string from the Maven config.
     * @param filePathPattern the rule's file-path pattern, used in the error message.
     * @return the parsed enum value.
     * @throws IllegalArgumentException if the value does not match a known mode.
     */
    private StaticTestSelectionRuleMode parseStaticTestSelectionRuleMode(final String raw,
                                                                         final String filePathPattern) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Static test selection rule '" + filePathPattern
                    + "': mode is required (one of RUN_ALL, SUITE_NAMES).");
        }
        try {
            return StaticTestSelectionRuleMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Static test selection rule '" + filePathPattern
                    + "': unknown mode '" + raw + "'. Expected one of RUN_ALL, SUITE_NAMES.");
        }
    }
}
