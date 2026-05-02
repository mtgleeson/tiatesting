package org.tiatesting.gradle.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logging;
import org.slf4j.Logger;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.report.html.HtmlReportGenerator;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryVersionPolicy;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.StatusReportGenerator;
import org.tiatesting.core.report.ReportGenerator;
import org.tiatesting.core.report.plaintext.TextReportGenerator;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        createStatusTask();
        createTextReportTask();
        createHtmlReportTask();
        createSelectTestsTask();
    }

    public void createStatusTask() {
        project.task("tia-status").doLast(task -> {
            final DataStore dataStore = new H2DataStore(resolveDbFilePath(), getVCSReader().getBranchName());
            StatusReportGenerator reportGenerator = new StatusReportGenerator();
            System.out.println(reportGenerator.generateSummaryReport(dataStore));
        });
    }

    public void createTextReportTask() {
        project.task("tia-text-report").doLast(task -> {
            System.out.println("Starting text report generation");
            final DataStore dataStore = new H2DataStore(resolveDbFilePath(), getVCSReader().getBranchName());
            TiaData tiaData = dataStore.getTiaData(true);
            File reportOutputDir = getReportOutputDir();
            ReportGenerator reportGenerator = new TextReportGenerator(getVCSReader().getBranchName(), reportOutputDir);
            reportGenerator.generateReports(tiaData);
            System.out.println("Text report generated successfully at " + reportOutputDir.getAbsolutePath());
        });
    }

    public void createHtmlReportTask() {
        project.task("tia-html-report").doLast(task -> {
            System.out.println("Starting HTML report generation");
            final DataStore dataStore = new H2DataStore(resolveDbFilePath(), getVCSReader().getBranchName());
            TiaData tiaData = dataStore.getTiaData(true);
            File reportOutputDir = getReportOutputDir();
            ReportGenerator reportGenerator = new HtmlReportGenerator(getVCSReader().getBranchName(), reportOutputDir);
            reportGenerator.generateReports(tiaData);
            System.out.println("HTML report generated successfully at " + reportOutputDir.getAbsolutePath());
        });
    }

    /**
     * Task to show the tests Tia will select for the workspace. Used to preview what tests Tia will select to run
     * without actually running the tests. Selection runs with {@code updateDBMapping=false}: library reconcile
     * and pending-stamp persistence are skipped, but drain analysis still runs (read-only) so the preview
     * matches what the test task would select.
     */
    public void createSelectTestsTask() {
        project.task("tia-select-tests").doLast(task -> {
            System.out.println("Displaying the tests selected by Tia.");
            final DataStore dataStore = new H2DataStore(resolveDbFilePath(), getVCSReader().getBranchName());
            List<String> sourceFilesDirs = getSourceFilesDirs() != null ? Arrays.asList(getSourceFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(sourceFilesDirs);
            List<String> testFilesDirs = getTestFilesDirs() != null ? Arrays.asList(getTestFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(testFilesDirs);
            TestSelector testSelector = new TestSelector(dataStore);
            LibraryImpactAnalysisConfig libraryConfig = buildLibraryImpactAnalysisConfig();
            TestSelectorResult result = testSelector.selectTestsToIgnore(getVCSReader(), sourceFilesDirs,
                    testFilesDirs, isCheckLocalChanges(), libraryConfig, false);
            Set<String> testsToRun = result.getTestsToRun();
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
     * @return should Tia check for unsubmitted changes
     */
    private boolean isCheckLocalChanges(){
        if (getUpdateDBMapping()){
            return false;
        } else {
            return getCheckLocalChanges();
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

    /**
     * Daemon-side tasks ({@code tia-select-tests}, {@code tia-status}, {@code tia-text-report},
     * {@code tia-html-report}) construct H2DataStore directly in the Gradle daemon. The daemon's
     * {@code user.dir} is set when the daemon process first starts and does not change between
     * builds, so a relative path like {@code "."} in {@code dbFilePath} resolves against the
     * daemon's cwd — not the project dir. The forked test JVM doesn't hit this because it gets a
     * per-build {@code workingDir = projectDir}. Resolve relative paths against {@code projectDir}
     * so daemon-side tasks find the same DB the test task wrote.
     *
     * @return the configured {@code dbFilePath} as an absolute path; relative paths are resolved
     *         against {@code project.getProjectDir()}.
     */
    public String resolveDbFilePath() {
        String path = getDbFilePath();
        if (path == null) {
            return null;
        }
        File f = new File(path);
        if (f.isAbsolute()) {
            return path;
        }
        return new File(project.getProjectDir(), path).getAbsolutePath();
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

    public String getSourceLibs() {
        return tiaTaskExtension.getSourceLibs();
    }

    public String getSourceProjectDir() {
        String dir = tiaTaskExtension.getSourceProjectDir();
        if (dir == null || dir.trim().isEmpty()) {
            return getProjectDir();
        }
        return dir;
    }

    public File getReportOutputDir() {
        if (tiaTaskExtension.getReportOutputDir() != null){
            return tiaTaskExtension.getReportOutputDir();
        }else{
            return new File(project.getLayout().getBuildDirectory().getAsFile().get().getPath() + File.separator + "tia/reports");
        }
    }

    /**
     * Build a {@link LibraryImpactAnalysisConfig} from the Gradle extension properties.
     *
     * @return the library impact analysis configuration parsed from the Gradle extension.
     */
    protected LibraryImpactAnalysisConfig buildLibraryImpactAnalysisConfig() {
        String libs = getSourceLibs();
        LibraryVersionPolicy policy = parseLibraryVersionPolicy(tiaTaskExtension.getLibraryVersionPolicy());
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
                LOGGER.warn("Invalid tiaSourceLibs entry '{}' — expected groupId:artifactId or groupId:artifactId:projectDir, skipping.", entry);
            }
        }

        LibraryJarResolver reader = new LibraryJarResolver(project, LOGGER);
        return new LibraryImpactAnalysisConfig(coordinates, libraryProjectDirs, getSourceProjectDir(), reader, policy);
    }

    private LibraryVersionPolicy parseLibraryVersionPolicy(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LibraryVersionPolicy.BUMP_AFTER_RELEASE;
        }
        try {
            return LibraryVersionPolicy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid libraryVersionPolicy value '{}' — expected BUMP_AT_RELEASE or BUMP_AFTER_RELEASE. Falling back to BUMP_AFTER_RELEASE.", raw);
            return LibraryVersionPolicy.BUMP_AFTER_RELEASE;
        }
    }

}
