package org.tiatesting.gradle.plugin;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TiaBaseTaskExtension {
    private String projectDir;
    private String classFilesDirs;
    private String sourceFilesDirs;
    private String sourceLibs;
    private String sourceProjectDir;
    private String testFilesDirs;
    private String dbFilePath;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;
    private String dbDialect;
    private Boolean enabled;
    private Boolean updateDBMapping;
    private Boolean updateDBTestRunHistory = Boolean.TRUE;
    private Boolean checkLocalChanges;
    private String runSource;
    private String schemaSuffix;
    private String libraryStampSchemas;
    private File reportOutputDir;
    private String buildDir;
    private List<GradleStaticTestSelectionRule> staticTestSelectionRules = new ArrayList<>();
    private Boolean distributed;
    private String runId;
    private Integer distributedGroupCount;
    private Long distributedTargetRunTime;
    private Integer distributedMaxGroups;
    private String distributedRunnerKey;

    @Input
    public String getProjectDir() {
        return projectDir;
    }

    public void setProjectDir(String projectDir) {
        this.projectDir = projectDir;
    }

    @Input
    public String getClassFilesDirs() {
        return classFilesDirs;
    }

    public void setClassFilesDirs(String classFilesDirs) {
        this.classFilesDirs = classFilesDirs;
    }

    @Input
    public String getSourceFilesDirs() {
        return sourceFilesDirs;
    }

    public void setSourceFilesDirs(String sourceFilesDirs) {
        this.sourceFilesDirs = sourceFilesDirs;
    }

    @Input
    @org.gradle.api.tasks.Optional
    public String getSourceLibs() {
        return sourceLibs;
    }

    public void setSourceLibs(String sourceLibs) {
        this.sourceLibs = sourceLibs;
    }

    @Input
    @org.gradle.api.tasks.Optional
    public String getSourceProjectDir() {
        return sourceProjectDir;
    }

    public void setSourceProjectDir(String sourceProjectDir) {
        this.sourceProjectDir = sourceProjectDir;
    }

    @Input
    public String getTestFilesDirs() {
        return testFilesDirs;
    }

    public void setTestFilesDirs(String testFilesDirs) {
        this.testFilesDirs = testFilesDirs;
    }

    @Input
    @org.gradle.api.tasks.Optional
    public String getDbFilePath() {
        return dbFilePath;
    }

    public void setDbFilePath(String dbFilePath) {
        this.dbFilePath = dbFilePath;
    }

    /**
     * @return the server-mode H2 JDBC URL, e.g. {@code jdbc:h2:tcp://h2host:9092/tiadb}, or
     *         {@code null} for embedded mode (in which case {@link #getDbFilePath()} is used)
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getDbUrl() {
        return dbUrl;
    }

    /**
     * @param dbUrl the server-mode H2 JDBC URL; when set, embedded {@code dbFilePath} is ignored
     */
    public void setDbUrl(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    /**
     * @return the server-mode H2 username, or {@code null} to use the default
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getDbUser() {
        return dbUser;
    }

    /**
     * @param dbUser the server-mode H2 username
     */
    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    /**
     * @return the server-mode H2 password, or {@code null} to use the default
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getDbPassword() {
        return dbPassword;
    }

    /**
     * @param dbPassword the server-mode H2 password
     */
    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }

    /**
     * @return the explicit SQL dialect id override (e.g. {@code "h2"}), or {@code null} to infer
     *         the dialect from {@link #getDbUrl()}
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getDbDialect() {
        return dbDialect;
    }

    /**
     * @param dbDialect the explicit SQL dialect id override; {@code null} to infer from {@link #getDbUrl()}
     */
    public void setDbDialect(String dbDialect) {
        this.dbDialect = dbDialect;
    }

    @Input
    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    @Input
    public Boolean getUpdateDBMapping() {
        return updateDBMapping;
    }

    public void setUpdateDBMapping(Boolean updateDBMapping) {
        this.updateDBMapping = updateDBMapping;
    }

    /**
     * The label recorded in the history row's {@code run_source} column, overriding Tia's own
     * detection.
     *
     * <p>Leave this unset unless the detection gets it wrong. Tia reads the CI marker environment
     * variables, which a forked test JVM inherits, so an ordinary CI job is already labelled
     * {@code CI} and a developer's machine {@code LOCAL} with nothing configured. Set it to
     * distinguish a build the detection cannot tell apart from any other - a nightly or a
     * performance rig - or to label a CI system Tia does not recognise.
     *
     * @return the declared run source, or null to let Tia detect it
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getRunSource() {
        return runSource;
    }

    /**
     * @param runSource the label to record in the history row's {@code run_source} column, or null
     *                  to let Tia detect it
     */
    public void setRunSource(String runSource) {
        this.runSource = runSource;
    }

    /**
     * The schema suffix isolating this test task's datastore from the other test tasks in the
     * project.
     *
     * <p>Two Tia-enabled test tasks sharing a datastore corrupt each other: each sees only its own
     * source set, so each deletes the other's tracked suites, and they share the one stored commit
     * value, so the one that ran less recently diffs from a commit it never covered. Declaring a
     * suffix per test task gives each its own schema - {@code tia_<branch>_<suffix>} - with its own
     * suite table and its own commit stamp.
     *
     * <p>Leave unset for a project with a single test task: the schema is then exactly the
     * {@code tia_<branch>} Tia has always used, so nothing moves.
     *
     * @return the declared schema suffix, or null for none
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getSchemaSuffix() {
        return schemaSuffix;
    }

    /**
     * @param schemaSuffix the schema suffix isolating this test task's datastore, or null for none
     */
    public void setSchemaSuffix(String schemaSuffix) {
        this.schemaSuffix = schemaSuffix;
    }

    /**
     * The schema suffixes a library publish stamp is written to, comma separated - the schemas of
     * the projects that <em>consume</em> this library.
     *
     * <p>Only needed by a project that publishes a tracked library to consumers which isolate their
     * test tasks into suffixed schemas. Tia cannot derive the list: the consuming app is a separate
     * build, so the library's own project has no visibility of its schemas at all. A stamp written
     * to a schema no consumer reads is never drained, and the suites the library change affects are
     * never re-run - silent under-selection - which is why this is declared rather than guessed.
     *
     * <p>Leave unset when the consumers use the plain {@code tia_<branch>} schema, which is the
     * single-test-task default: the stamp then goes where it always did.
     *
     * <p>Comma separated rather than a list because that is how every other multi-valued Tia
     * setting is expressed, and because a Maven {@code List} parameter cannot be driven from a
     * single property - which would break the centralised parent-pom configuration real projects
     * use.
     *
     * @return the consuming schemas' suffixes as a comma-separated string, or null for none
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getLibraryStampSchemas() {
        return libraryStampSchemas;
    }

    /**
     * @param libraryStampSchemas the consuming schemas' suffixes, comma separated, or null for none
     */
    public void setLibraryStampSchemas(String libraryStampSchemas) {
        this.libraryStampSchemas = libraryStampSchemas;
    }

    /**
     * @return whether this run should log a row to the {@code tia_test_run_history} table.
     *         Defaults to {@code true}.
     */
    @Input
    public Boolean getUpdateDBTestRunHistory() {
        return updateDBTestRunHistory;
    }

    /**
     * @param updateDBTestRunHistory whether this run should log a row to the
     *                               {@code tia_test_run_history} table.
     */
    public void setUpdateDBTestRunHistory(Boolean updateDBTestRunHistory) {
        this.updateDBTestRunHistory = updateDBTestRunHistory;
    }

    @Input
    public Boolean getCheckLocalChanges() {
        return checkLocalChanges;
    }

    public void setCheckLocalChanges(Boolean checkLocalChanges) {
        this.checkLocalChanges = checkLocalChanges;
    }

    @Input
    @OutputDirectory
    public File getReportOutputDir() {
        return reportOutputDir;
    }

    public void setReportOutputDir(File reportOutputDir) {
        this.reportOutputDir = reportOutputDir;
    }

    /**
     * @return the configured directory the {@code tia-dist-plan} task writes {@code
     *         tia-run-plan.json} under, or {@code null} to use {@link TiaBasePlugin#getTiaBuildDir()}'s
     *         default of {@code <project build dir>/tia} - the Gradle analog of the Maven goal's
     *         {@code tiaBuildDir} property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getBuildDir() {
        return buildDir;
    }

    /**
     * @param buildDir the directory the distributed run plan file is written under; {@code null}
     *                 (the default) falls back to {@code <project build dir>/tia}, mirroring the
     *                 Maven goal's {@code tiaBuildDir} parameter and its {@code
     *                 ${project.build.directory}/tia} default
     */
    public void setBuildDir(String buildDir) {
        this.buildDir = buildDir;
    }

    /**
     * Static test selection rules. Each rule maps a regex over the repo-relative paths of
     * changed files to a set of test suites that should be force-run regardless of dynamic
     * coverage-based selection. Rules are additive: their selected suites are unioned into
     * the dynamic test selection.
     *
     * <p>Configured via the Gradle DSL, e.g.
     * <pre>{@code
     * tia {
     *     staticTestSelectionRules = [
     *         [name: "db-migrations",
     *          filePathPattern: "src/main/resources/db/migrations/.*\\.sql\\$",
     *          mode: "SUITE_NAMES",
     *          suiteNamePatterns: [".*MigrationIT\\$"]]
     *     ]
     * }
     * }</pre>
     *
     * @return the configured rules; never {@code null}, may be empty.
     */
    @Nested
    @org.gradle.api.tasks.Optional
    public List<GradleStaticTestSelectionRule> getStaticTestSelectionRules() {
        return staticTestSelectionRules;
    }

    /**
     * @param staticTestSelectionRules the static test selection rules; {@code null} is treated as empty.
     */
    public void setStaticTestSelectionRules(List<GradleStaticTestSelectionRule> staticTestSelectionRules) {
        this.staticTestSelectionRules = (staticTestSelectionRules != null)
                ? staticTestSelectionRules
                : Collections.emptyList();
    }

    /**
     * @return whether this build participates in a distributed test run: the tests Tia selects are
     *         split into groups and persisted to a shared database instead of all running in this
     *         one build. Mirrors the Maven {@code tiaDistributed} parameter (see {@code
     *         AbstractTiaMojo#isTiaDistributed()}); the claim, made in the build JVM on both build
     *         tools (see the "Distributed test runs" chapter in {@code WIKI.md}), branches on this
     *         master switch.
     */
    @Input
    @org.gradle.api.tasks.Optional
    public Boolean getDistributed() {
        return distributed;
    }

    /**
     * @param distributed whether this build participates in a distributed test run; distributed
     *                    runs also require a shared datastore ({@link #getDbUrl()}) and {@link
     *                    #getCheckLocalChanges()} disabled - see {@code DistributedRunPreconditions}
     *                    in {@code tia-core}
     */
    public void setDistributed(Boolean distributed) {
        this.distributed = distributed;
    }

    /**
     * @return the configured distributed run's shared identifier, or {@code null} if not
     *         configured. Required by the {@code tia-dist-plan} task; read but not required by the
     *         {@code tia-select-tests} grouping preview, which has no run id to report.
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getRunId() {
        return runId;
    }

    /**
     * @param runId the distributed run's shared identifier ({@code tia.runId}); every runner in
     *              the distributed run must agree on it to find each other's rows in the shared
     *              datastore
     */
    public void setRunId(String runId) {
        this.runId = runId;
    }

    /**
     * @return the configured fixed number of groups to split a distributed run's selected tests
     *         into, or {@code null} to use a target run time instead
     */
    @Input
    @org.gradle.api.tasks.Optional
    public Integer getDistributedGroupCount() {
        return distributedGroupCount;
    }

    /**
     * @param distributedGroupCount the fixed number of groups to split into; mutually exclusive
     *                              with {@link #setDistributedTargetRunTime(Long)} - exactly one
     *                              of the two must be set for a real distributed run, though
     *                              either alone is enough to enable the {@code tia-select-tests}
     *                              grouping preview
     */
    public void setDistributedGroupCount(Integer distributedGroupCount) {
        this.distributedGroupCount = distributedGroupCount;
    }

    /**
     * @return the configured target wall-clock run time in ms for a distributed run, or {@code
     *         null} to use a fixed group count instead
     */
    @Input
    @org.gradle.api.tasks.Optional
    public Long getDistributedTargetRunTime() {
        return distributedTargetRunTime;
    }

    /**
     * @param distributedTargetRunTime the target wall-clock run time in ms; mutually exclusive
     *                                 with {@link #setDistributedGroupCount(Integer)} - exactly
     *                                 one of the two must be set for a real distributed run
     */
    public void setDistributedTargetRunTime(Long distributedTargetRunTime) {
        this.distributedTargetRunTime = distributedTargetRunTime;
    }

    /**
     * @return the configured ceiling on the group count for a distributed run, or {@code null}
     *         for no ceiling. Only meaningful alongside {@link #getDistributedTargetRunTime()}.
     */
    @Input
    @org.gradle.api.tasks.Optional
    public Integer getDistributedMaxGroups() {
        return distributedMaxGroups;
    }

    /**
     * @param distributedMaxGroups an optional ceiling on the group count; only meaningful
     *                             alongside a configured target run time, since a ceiling on a
     *                             fixed group count would be either a no-op or a contradiction
     */
    public void setDistributedMaxGroups(Integer distributedMaxGroups) {
        this.distributedMaxGroups = distributedMaxGroups;
    }

    /**
     * @return the configured per-runner identity value for a distributed run, or {@code null} to
     *         let the claim protocol derive one from the run id, hostname and process id
     */
    @Input
    @org.gradle.api.tasks.Optional
    public String getDistributedRunnerKey() {
        return distributedRunnerKey;
    }

    /**
     * @param distributedRunnerKey an optional per-runner identity value; not validated or used by
     *                             this class, since it is read only by the claim protocol
     */
    public void setDistributedRunnerKey(String distributedRunnerKey) {
        this.distributedRunnerKey = distributedRunnerKey;
    }

}
