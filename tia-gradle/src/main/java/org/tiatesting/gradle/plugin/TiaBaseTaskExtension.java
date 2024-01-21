package org.tiatesting.gradle.plugin;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;

import java.io.File;

public class TiaBaseTaskExtension {
    private String projectDir;
    private String classFilesDirs;
    private String sourceFilesDirs;
    private String testFilesDirs;
    private String dbFilePath;
    private Boolean enabled;
    private Boolean updateDBMapping;
    private Boolean updateDBStats;
    private Boolean checkLocalChanges;
    private File reportOutputDir;

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

}
