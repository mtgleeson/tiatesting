package org.tiatesting.gradle.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logging;
import org.slf4j.Logger;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.InfoReportGenerator;
import org.tiatesting.core.report.ReportGenerator;
import org.tiatesting.core.report.TextFileReportGenerator;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Base Gradle plugin for Tia. Creates the new standard tasks for interacting with Tia.
 * It's an abstract class intended to be extended for the implementation specific plugins.
 */
public abstract class TiaBasePlugin implements Plugin<Project> {

    private static final Logger LOGGER = Logging.getLogger(TiaBasePlugin.class);

    private TiaBaseTaskExtension tiaTaskExtension;
    private Project project;

    @Override
    public void apply(Project project) {
        this.project = project;
        this.tiaTaskExtension = project.getExtensions().create("tia", TiaBaseTaskExtension.class);
        createInfoTask();
        createTextReportTask();
        createSelectTestsTask();
    }

    public void createInfoTask() {
        project.task("tia-info").doLast(task -> {
            final DataStore dataStore = new H2DataStore(getDbFilePath(), getVCSReader().getBranchName());
            ReportGenerator reportGenerator = new InfoReportGenerator();
            System.out.println(reportGenerator.generateReport(dataStore));
        });
    }

    public void createTextReportTask() {
        project.task("tia-text-report").doLast(task -> {
            System.out.println("Starting text report generation");
            final DataStore dataStore = new H2DataStore(getDbFilePath(), getVCSReader().getBranchName());
            ReportGenerator reportGenerator = new TextFileReportGenerator(getVCSReader().getBranchName(), getReportOutputDir());
            String fileName = reportGenerator.generateReport(dataStore);
            System.out.println("Text report generated successfully at " + fileName);
        });
    }

    /**
     * Task to show the tests Tia will select for the workspace. Used to preview what tests Tia will select to run
     * without actually running the tests.
     */
    public void createSelectTestsTask() {
        project.task("tia-select-tests").doLast(task -> {
            System.out.println("Displaying the tests selected by Tia.");
            final DataStore dataStore = new H2DataStore(getDbFilePath(), getVCSReader().getBranchName());
            List<String> sourceFilesDirs = getSourceFilesDirs() != null ? Arrays.asList(getSourceFilesDirs().split(",")) : null;
            List<String> testFilesDirs = getTestFilesDirs() != null ? Arrays.asList(getTestFilesDirs().split(",")) : null;
            TestSelector testSelector = new TestSelector(dataStore);
            Set<String> testsToRun = testSelector.selectTestsToRun(getVCSReader(), sourceFilesDirs, testFilesDirs, isCheckLocalChanges());
            String lineSep = System.lineSeparator();

            System.out.println("Selected tests to run: ");
            if (testsToRun.isEmpty()){
                System.out.println("none");
            } else {
                System.out.println("\t" + testsToRun.stream().map(String::valueOf).collect(
                        Collectors.joining(lineSep + "\t", "", "")));
            }
        });
    }

    /**
     * Check if Tia should analyze local changes.
     * If we're updating the DB, we shouldn't check for local changes as the DB needs to be in sync with
     * committed changes only.
     *
     * @return
     */
    private boolean isCheckLocalChanges(){
        if (Boolean.valueOf(getUpdateDBMapping())){
            return false;
        } else{
            return Boolean.valueOf(getCheckLocalChanges());
        }
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

    public Boolean getEnabled() {
        return tiaTaskExtension.getEnabled();
    }

    public Boolean getUpdateDBMapping() {
        return tiaTaskExtension.getUpdateDBMapping();
    }

    public Boolean getUpdateDBStats() {
        return tiaTaskExtension.getUpdateDBStats();
    }

    public Boolean getCheckLocalChanges() {
        return tiaTaskExtension.getCheckLocalChanges();
    }

    public File getReportOutputDir() {
        if (tiaTaskExtension.getReportOutputDir() != null){
            return tiaTaskExtension.getReportOutputDir();
        }else{
            return new File(project.getBuildDir().getPath() + File.separator + "tia");
        }
    }

}
