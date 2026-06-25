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
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.diff.diffanalyze.selector.SelectTestsOutputFormatter;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.LibrariesReportGenerator;
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
        createLibrariesTask();
        createTextReportTask();
        createHtmlReportTask();
        createSelectTestsTask();
        createHistoryTask();
    }

    public void createStatusTask() {
        project.task("tia-status").doLast(task -> {
            try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(getVCSReader().getBranchName()))) {
                StatusReportGenerator reportGenerator = new StatusReportGenerator();
                System.out.println(reportGenerator.generateSummaryReport(dataStore));
            }
        });
    }

    /**
     * Task to print the tracked libraries and their state (project dir, source dirs, versions,
     * pending impacted-method batches) to stdout. Mirrors {@link #createStatusTask()} in shape;
     * the status task intentionally no longer includes library information.
     */
    public void createLibrariesTask() {
        project.task("tia-libraries").doLast(task -> {
            try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(getVCSReader().getBranchName()))) {
                LibrariesReportGenerator reportGenerator = new LibrariesReportGenerator();
                System.out.println(reportGenerator.generateLibrariesReport(dataStore));
            }
        });
    }

    public void createTextReportTask() {
        project.task("tia-text-report").doLast(task -> {
            System.out.println("Starting text report generation");
            try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(getVCSReader().getBranchName()))) {
                TiaData tiaData = dataStore.getTiaData(true);
                File reportOutputDir = getReportOutputDir();
                ReportGenerator reportGenerator = new TextReportGenerator(getVCSReader().getBranchName(), reportOutputDir);
                reportGenerator.generateReports(tiaData);
                System.out.println("Text report generated successfully at " + reportOutputDir.getAbsolutePath());
            }
        });
    }

    public void createHtmlReportTask() {
        project.task("tia-html-report").doLast(task -> {
            System.out.println("Starting HTML report generation");
            try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(getVCSReader().getBranchName()))) {
                TiaData tiaData = dataStore.getTiaData(true);
                File reportOutputDir = getReportOutputDir();
                ReportGenerator reportGenerator = new HtmlReportGenerator(getVCSReader().getBranchName(), reportOutputDir);
                reportGenerator.generateReports(tiaData);
                System.out.println("HTML report generated successfully at " + reportOutputDir.getAbsolutePath());
            }
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
            try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(getVCSReader().getBranchName()))) {
                List<String> sourceFilesDirs = getSourceFilesDirs() != null ? Arrays.asList(getSourceFilesDirs().split(",")) : null;
                StringUtil.sanitizeInputArray(sourceFilesDirs);
                List<String> testFilesDirs = getTestFilesDirs() != null ? Arrays.asList(getTestFilesDirs().split(",")) : null;
                StringUtil.sanitizeInputArray(testFilesDirs);
                TestSelector testSelector = new TestSelector(dataStore);
                LibraryImpactAnalysisConfig libraryConfig = buildLibraryImpactAnalysisConfig();
                StaticTestSelectionConfig staticMappingConfig = buildStaticTestSelectionConfig();
                // Read-only preview: no mapping writes (updateDBMapping=false).
                TestSelectorResult result = testSelector.selectTestsToIgnore(getVCSReader(), sourceFilesDirs,
                        testFilesDirs, isCheckLocalChanges(), libraryConfig, staticMappingConfig, false);
                Set<String> testsToRun = result.getTestsToRun();
                String lineSep = System.lineSeparator();

                System.out.println("Selected tests to run: ");
                if (testsToRun.isEmpty()){
                    System.out.println("none");
                } else {
                    System.out.println(SelectTestsOutputFormatter.formatSelectedTestsList(result, lineSep));
                    // Include the mapping overhead in the estimate when the actual run being
                    // previewed will collect coverage (the configured updateDBMapping).
                    System.out.println(SelectTestsOutputFormatter.formatEstimateBlock(result, lineSep,
                            Boolean.TRUE.equals(getUpdateDBMapping())));
                }
            }
        });
    }

    /**
     * Task to print the most recent rows from {@code tia_test_run_history} to stdout.
     * Mirrors {@link #createSelectTestsTask()} in shape but registers a {@link TiaHistoryTask}
     * subclass instead of an inline {@code doLast} closure so the {@code --last N} CLI flag
     * can be wired in via Gradle's {@code @Option} machinery. Default cap is 20.
     */
    public void createHistoryTask() {
        project.getTasks().register("tia-history", TiaHistoryTask.class, task -> {
            task.setVcsReaderSupplier(this::getVCSReader);
            task.setConnectionSettingsFactory(this::buildH2ConnectionSettings);
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

    public String getDbUrl() {
        return tiaTaskExtension.getDbUrl();
    }

    public String getDbUser() {
        return tiaTaskExtension.getDbUser();
    }

    public String getDbPassword() {
        return tiaTaskExtension.getDbPassword();
    }

    /**
     * Resolve the H2 connection settings for the daemon-side Tia tasks. Picks server mode when
     * {@code dbUrl} is configured, otherwise embedded mode using {@link #resolveDbFilePath()}
     * (which resolves a relative {@code dbFilePath} against the project dir, not the daemon cwd).
     *
     * @param branchSuffix the VCS branch name, used as the embedded-mode file suffix
     * @return the resolved embedded- or server-mode connection settings
     */
    public H2ConnectionSettings buildH2ConnectionSettings(String branchSuffix) {
        return H2ConnectionSettings.fromConfig(resolveDbFilePath(), getDbUrl(), getDbUser(),
                getDbPassword(), branchSuffix);
    }

    /**
     * Daemon-side tasks ({@code tia-select-tests}, {@code tia-status}, {@code tia-text-report},
     * {@code tia-html-report}) construct H2DataStore directly in the Gradle daemon. The daemon's
     * {@code user.dir} is set when the daemon process first starts and does not change between
     * builds, so a relative path like {@code "."} in {@code dbFilePath} resolves against the
     * daemon's cwd - not the project dir. The forked test JVM doesn't hit this because it gets a
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

    /**
     * @return whether the current run should log a row to {@code tia_test_run_history}.
     */
    public Boolean getUpdateDBTestRunHistory() {
        return tiaTaskExtension.getUpdateDBTestRunHistory();
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
                LOGGER.warn("Invalid tiaSourceLibs entry '{}' - expected groupId:artifactId or groupId:artifactId:projectDir, skipping.", entry);
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
            LOGGER.warn("Invalid libraryVersionPolicy value '{}' - expected BUMP_AT_RELEASE or BUMP_AFTER_RELEASE. Falling back to BUMP_AFTER_RELEASE.", raw);
            return LibraryVersionPolicy.BUMP_AFTER_RELEASE;
        }
    }

    /**
     * Build the static test selection configuration from the Gradle extension's
     * {@code staticTestSelectionRules} list. Validates each entry, parses its mode, and
     * pre-compiles its regex patterns. Returns {@link StaticTestSelectionConfig#EMPTY} when
     * no rules are configured.
     *
     * @return the parsed static test selection config.
     * @throws IllegalArgumentException if any rule is missing required fields, has an unknown
     *                                  mode, or contains an invalid regex.
     */
    protected StaticTestSelectionConfig buildStaticTestSelectionConfig() {
        return buildStaticTestSelectionConfig(tiaTaskExtension.getStaticTestSelectionRules());
    }

    /**
     * Build a {@link StaticTestSelectionConfig} from a list of Gradle-side rule POJOs.
     * Shared by the in-plugin {@code tia-select-tests} task and by the Spock-Gradle bridge
     * that forwards the config to the forked test JVM via system properties, so both paths
     * apply identical validation and parsing.
     *
     * @param rawRules the rule POJOs collected from the {@code tia} extension; {@code null}
     *                 or empty yields {@link StaticTestSelectionConfig#EMPTY}.
     * @return the parsed static test selection config.
     * @throws IllegalArgumentException if any rule is missing required fields, has an unknown
     *                                  mode, or contains an invalid regex.
     */
    public static StaticTestSelectionConfig buildStaticTestSelectionConfig(
            final List<GradleStaticTestSelectionRule> rawRules) {
        if (rawRules == null || rawRules.isEmpty()) {
            return StaticTestSelectionConfig.EMPTY;
        }

        List<StaticTestSelectionRule> compiledRules = new ArrayList<>(rawRules.size());
        for (GradleStaticTestSelectionRule raw : rawRules) {
            StaticTestSelectionRuleMode mode = parseStaticTestSelectionRuleMode(raw.getMode(), raw.getFilePathPattern());
            compiledRules.add(new StaticTestSelectionRule(
                    raw.getName(), raw.getFilePathPattern(), mode, raw.getSuiteNamePatterns()));
        }
        return new StaticTestSelectionConfig(compiledRules);
    }

    /**
     * Parse the raw mode string from the Gradle DSL into the core enum. Empty or unknown
     * values produce a clear error rather than a silent default; we'd rather fail the build
     * than mis-route a rule.
     *
     * @param raw the raw mode string from the Gradle DSL.
     * @param filePathPattern the rule's file-path pattern, used in the error message.
     * @return the parsed enum value.
     * @throws IllegalArgumentException if the value does not match a known mode.
     */
    private static StaticTestSelectionRuleMode parseStaticTestSelectionRuleMode(final String raw,
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
