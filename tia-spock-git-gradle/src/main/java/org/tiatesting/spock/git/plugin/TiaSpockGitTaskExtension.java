package org.tiatesting.spock.git.plugin;

import org.gradle.api.tasks.Input;

public class TiaSpockGitTaskExtension {
    private String projectDir;
    private String classFilesDirs;
    private String sourceFilesDirs;
    private String dbFilePath;
    private String dbPersistenceStrategy;
    private boolean enabled;
    private boolean updateDB;

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
    public String getDbFilePath() {
        return dbFilePath;
    }

    public void setDbFilePath(String dbFilePath) {
        this.dbFilePath = dbFilePath;
    }

    @Input
    public String getDbPersistenceStrategy() {
        return dbPersistenceStrategy;
    }

    public void setDbPersistenceStrategy(String dbPersistenceStrategy) {
        this.dbPersistenceStrategy = dbPersistenceStrategy;
    }

    @Input
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Input
    public boolean isUpdateDB() {
        return updateDB;
    }

    public void setUpdateDB(boolean updateDB) {
        this.updateDB = updateDB;
    }
}
