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
    private String libraryVersionPolicy;
    private String testFilesDirs;
    private String dbFilePath;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;
    private Boolean enabled;
    private Boolean updateDBMapping;
    private Boolean updateDBStats;
    private Boolean updateDBTestRunHistory = Boolean.TRUE;
    private Boolean checkLocalChanges;
    private File reportOutputDir;
    private List<GradleStaticTestSelectionRule> staticTestSelectionRules = new ArrayList<>();

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
    @org.gradle.api.tasks.Optional
    public String getLibraryVersionPolicy() {
        return libraryVersionPolicy;
    }

    public void setLibraryVersionPolicy(String libraryVersionPolicy) {
        this.libraryVersionPolicy = libraryVersionPolicy;
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

}
