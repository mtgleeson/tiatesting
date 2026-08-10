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
    private Boolean updateDBStats;
    private Boolean updateDBTestRunHistory = Boolean.TRUE;
    private Boolean checkLocalChanges;
    private File reportOutputDir;
    private List<GradleStaticTestSelectionRule> staticTestSelectionRules = new ArrayList<>();
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

    public Boolean getUpdateDBStats() {
        return updateDBStats;
    }

    public void setUpdateDBStats(Boolean updateDBStats) {
        this.updateDBStats = updateDBStats;
    }

    public void setUpdateDBMapping(Boolean updateDBMapping) {
        this.updateDBMapping = updateDBMapping;
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
