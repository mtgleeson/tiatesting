package org.tiatesting.gradle.plugin;

import org.gradle.api.tasks.Input;

public class TiaBaseTaskExtension {
    private String projectDir;
    private String classFilesDirs;
    private String sourceFilesDirs;
    private String testFilesDirs;
    private String dbFilePath;
    private String enabled;
    private String updateDBMapping;
    private String updateDBStats;
    private String checkLocalChanges;
    private String reportOutputDir;

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
    public String getTestFilesDirs() {
        return testFilesDirs;
    }

    public void setTestFilesDirs(String testFilesDirs) {
        this.testFilesDirs = testFilesDirs;
    }

    @Input
    public String getDbFilePath() {
        return dbFilePath;
    }

    public void setDbFilePath(String dbFilePath) {
        this.dbFilePath = dbFilePath;
    }

    @Input
    public String getEnabled() {
        return enabled;
    }

    public void setEnabled(String enabled) {
        this.enabled = enabled;
    }

    @Input
    public String getUpdateDBMapping() {
        return updateDBMapping;
    }

    public String getUpdateDBStats() {
        return updateDBStats;
    }

    public void setUpdateDBStats(String updateDBStats) {
        this.updateDBStats = updateDBStats;
    }

    public void setUpdateDBMapping(String updateDBMapping) {
        this.updateDBMapping = updateDBMapping;
    }

    @Input
    public String getCheckLocalChanges() {
        return checkLocalChanges;
    }

    public void setCheckLocalChanges(String checkLocalChanges) {
        this.checkLocalChanges = checkLocalChanges;
    }

    @Input
    public String getReportOutputDir() {
        return reportOutputDir;
    }

    public void setReportOutputDir(String reportOutputDir) {
        this.reportOutputDir = reportOutputDir;
    }

}
