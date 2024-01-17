package org.tiatesting.gradle.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.report.InfoReportGenerator;
import org.tiatesting.report.ReportGenerator;
import org.tiatesting.report.TextFileReportGenerator;

/**
 * Base Gradle plugin for Tia. Creates the new standard tasks for interacting with Tia.
 * It's an abstract class intended to be extended for the implementation specific plugins.
 */
public abstract class TiaBasePlugin implements Plugin<Project> {

    private TiaBaseTaskExtension tiaTaskExtension;
    private Project project;

    @Override
    public void apply(Project project) {
        this.project = project;
        this.tiaTaskExtension = project.getExtensions().create("tia", TiaBaseTaskExtension.class);
        createInfoTask();
        createTextReportTask();
    }

    public void createInfoTask() {
        project.task("tia-info").doLast(task -> {
            final DataStore dataStore = new MapDataStore(getDbFilePath(), getVCSReader().getBranchName());
            ReportGenerator reportGenerator = new InfoReportGenerator();
            System.out.println(reportGenerator.generateReport(dataStore));
        });
    }

    public void createTextReportTask() {
        project.task("tia-text-report").doLast(task -> {
            System.out.println("Starting text report generation");
            final DataStore dataStore = new MapDataStore(getDbFilePath(), getVCSReader().getBranchName());
            ReportGenerator reportGenerator = new TextFileReportGenerator(getVCSReader().getBranchName());
            reportGenerator.generateReport(dataStore);
            System.out.println("Text report generated successfully");
        });
    }

    public abstract VCSReader getVCSReader();

    public String getProjectDir() {
        return tiaTaskExtension.getProjectDir();
    }

    public String getClassFilesDirs() {
        return tiaTaskExtension.getClassFilesDirs();
    }

    public String getSourceFilesDirs() {
        return tiaTaskExtension.getSourceFilesDirs();
    }

    public String getTestFilesDirs() {
        return tiaTaskExtension.getTestFilesDirs();
    }

    public String getDbFilePath() {
        return tiaTaskExtension.getDbFilePath();
    }

    public String getEnabled() {
        return tiaTaskExtension.getEnabled();
    }

    public String getUpdateDBMapping() {
        return tiaTaskExtension.getUpdateDBMapping();
    }

    public String getUpdateDBStats() {
        return tiaTaskExtension.getUpdateDBStats();
    }

    public String getCheckLocalChanges() {
        return tiaTaskExtension.getCheckLocalChanges();
    }

}
