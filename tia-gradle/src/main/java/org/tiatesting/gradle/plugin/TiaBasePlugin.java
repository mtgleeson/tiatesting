package org.tiatesting.gradle.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.tiatesting.core.library.LibraryPublishStamper;
import org.slf4j.Logger;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.report.html.HtmlReportGenerator;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.diff.diffanalyze.selector.SelectTestsOutputFormatter;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.distributed.DistributedRunPlanner;
import org.tiatesting.core.distributed.DistributedRunPreviewFormatter;
import org.tiatesting.core.distributed.GroupingResult;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
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
        createLibraryPublishesTask();
        createLibraryPendingMethodsTask();
        createDistPlanTask();
        hookPublishStampTasks();
    }

    /**
     * Register the {@code tia-dist-plan} task - plans a distributed test run by running the same
     * test selection a normal build would, then splitting the selected suites into groups
     * persisted to the shared database so a CI pipeline can fan out one job per group. Mirrors the
     * Maven {@code tia-dist-plan} goal's sequence exactly: both call {@link
     * org.tiatesting.core.distributed.DistributedRunPreconditions#check}, build a {@code
     * DistributedRunConfig}, run selection, hand it to {@link
     * org.tiatesting.core.distributed.DistributedRunPlanner#plan}, and write the result via {@link
     * org.tiatesting.core.distributed.DistributedRunPlanWriter} - the same writer class the Maven
     * goal uses, so the two build tools cannot drift on the file's format. Registered like {@link
     * #createHistoryTask()} and {@link #createLibraryPublishesTask()}: a dedicated task class with
     * its dependencies injected at registration time rather than resolved at plugin-apply time.
     */
    public void createDistPlanTask() {
        project.getTasks().register("tia-dist-plan", TiaDistPlanTask.class, task -> {
            task.setPlugin(this);
        });
    }

    /**
     * Register the {@code tia-library-publishes} task - prints a tracked library's publish
     * ledger as a table. The library is selected with the {@code --library=groupId:artifactId}
     * option; mirrors {@link #createHistoryTask()} in shape.
     */
    public void createLibraryPublishesTask() {
        project.getTasks().register("tia-library-publishes", TiaLibraryPublishesTask.class, task -> {
            task.setVcsReaderSupplier(this::getVCSReader);
            task.setDataStoreFactory(this::buildDataStore);
        });
    }

    /**
     * Register the {@code tia-library-pending-methods} task - prints a tracked library's pending
     * impacted methods as a table. The library is selected with the
     * {@code --library=groupId:artifactId} option; mirrors {@link #createHistoryTask()} in shape.
     */
    public void createLibraryPendingMethodsTask() {
        project.getTasks().register("tia-library-pending-methods", TiaLibraryPendingMethodsTask.class, task -> {
            task.setVcsReaderSupplier(this::getVCSReader);
            task.setDataStoreFactory(this::buildDataStore);
        });
    }

    public void createStatusTask() {
        project.task("tia-status").doLast(task -> {
            try (DataStore dataStore = buildDataStore(getVCSReader().getBranchName())) {
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
            try (DataStore dataStore = buildDataStore(getVCSReader().getBranchName())) {
                LibrariesReportGenerator reportGenerator = new LibrariesReportGenerator();
                System.out.println(reportGenerator.generateLibrariesReport(dataStore));
            }
        });
    }

    public void createTextReportTask() {
        project.task("tia-text-report").doLast(task -> {
            System.out.println("Starting text report generation");
            try (DataStore dataStore = buildDataStore(getVCSReader().getBranchName())) {
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
            try (DataStore dataStore = buildDataStore(getVCSReader().getBranchName())) {
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
            try (DataStore dataStore = buildDataStore(getVCSReader().getBranchName())) {
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
                    printDistributedRunPreviewIfConfigured(result, lineSep);
                }
            }
        });
    }

    /**
     * Print the distributed run grouping preview when the user has configured a distributed run
     * group count or target run time in the {@code tia { ... }} extension, so a developer running
     * {@code tia-select-tests} can see how the selection would be split across runners without
     * creating an actual plan. A user who has not configured either property sees no change at all
     * in this task's output - {@link #getDistributedGroupCount()} and {@link
     * #getDistributedTargetRunTime()} are both {@code null} unless explicitly set, so this method
     * is a no-op for every non-distributed build.
     *
     * <p>Calls {@link DistributedRunPlanner#balance}, never {@link DistributedRunPlanner#plan} -
     * {@code plan} persists a claimable run to the shared database, which a preview must not do.
     * It also does not build a {@code DistributedRunConfig} or call {@code
     * DistributedRunPreconditions.check}: a config requires a {@code tia.runId} this task does not
     * have, and previewing against an embedded database - which a real distributed run would
     * reject - is a legitimate thing to want here since nothing is written.
     *
     * <p>{@code tia-select-tests} is a read-only task every developer runs, often against a shared
     * convention plugin's distributed-run properties that developer did not set and may not even
     * be aware of; a misconfiguration in those properties (for example both {@link
     * #getDistributedGroupCount()} and {@link #getDistributedTargetRunTime()} set) must not throw
     * out of this task's {@code doLast} closure and abort the build. {@link
     * DistributedRunPlanner#balance} throws {@link IllegalArgumentException} for every way the
     * grouping shape can be invalid, so that is caught here and printed as a skip notice instead of
     * propagating - the real {@code tia-dist-plan} task is still the one place a bad configuration
     * fails the build.
     *
     * @param selection the test selection already computed by {@link #createSelectTestsTask()},
     *                   whose selected suites and their estimated run times are what the preview
     *                   balances
     * @param lineSep the line separator to use between lines, matching the rest of this task's
     *                output
     */
    void printDistributedRunPreviewIfConfigured(final TestSelectorResult selection, final String lineSep) {
        Integer groupCount = getDistributedGroupCount();
        Long targetRunTimeMs = getDistributedTargetRunTime();
        if (groupCount == null && targetRunTimeMs == null) {
            return;
        }

        try {
            GroupingResult grouping = DistributedRunPlanner.balance(selection,
                    Boolean.TRUE.equals(getUpdateDBMapping()), groupCount, targetRunTimeMs,
                    getDistributedMaxGroups());
            System.out.println(DistributedRunPreviewFormatter.formatPreview(grouping, targetRunTimeMs, lineSep));
        } catch (IllegalArgumentException e) {
            System.out.println("Distributed run grouping preview skipped: " + e.getMessage());
        }
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
            task.setDataStoreFactory(this::buildDataStore);
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

    /**
     * Hook the Tia publish stamp onto this project's Maven publish tasks so a library module
     * publishing an artifact records the build in the publish ledger and stamps the source
     * methods impacted since its mapping baseline. Matches the aggregate {@code publish} task
     * (remote repositories) and {@code publishToMavenLocal} (the local {@code ~/.m2} analog of
     * {@code mvn install}, where a local consumer build resolves from) by name via
     * {@code configureEach}, so the hook attaches whether the {@code maven-publish} plugin is
     * applied before or after Tia and is a silent no-op on projects that never publish.
     * See the library publish-time stamping chapter in {@code WIKI.md}.
     */
    private void hookPublishStampTasks() {
        project.getTasks().configureEach(task -> {
            if ("publish".equals(task.getName()) || "publishToMavenLocal".equals(task.getName())) {
                task.doLast(t -> stampPublish());
            }
        });
    }

    /**
     * Record this project's publish in the Tia publish ledger and stamp its impacted methods,
     * evaluating this project's own configured static test selection rules (built the same way
     * as the {@code tia-select-tests} task, via {@link #buildStaticTestSelectionConfig()}) against
     * the files changed since the previous publish. No-ops when Tia is disabled or this build does
     * not own mapping-DB writes ({@code updateDBMapping=false}, e.g. a developer machine against a
     * shared DB - the local development flow is covered app-side without persisted stamps). The
     * stamper itself skips, with a warning, when this project is not a tracked library in the Tia DB.
     */
    private void stampPublish() {
        if (!Boolean.TRUE.equals(getEnabled())) {
            LOGGER.debug("Tia is disabled - skipping publish stamp.");
            return;
        }
        if (!Boolean.TRUE.equals(getUpdateDBMapping())) {
            LOGGER.info("Tia publish stamp skipped: this build does not own mapping-DB writes "
                    + "(updateDBMapping=false).");
            return;
        }

        String groupArtifact = project.getGroup() + ":" + project.getName();
        String publishedVersion = String.valueOf(project.getVersion());
        String jarFilePath = resolveBuiltArchivePath();

        VCSReader vcsReader = getVCSReader();
        StaticTestSelectionConfig staticConfig = buildStaticTestSelectionConfig();
        try (DataStore dataStore = buildDataStore(vcsReader.getBranchName())) {
            LibraryPublishStamper.PublishStampResult result = new LibraryPublishStamper()
                    .stampPublish(dataStore, vcsReader, groupArtifact, publishedVersion, jarFilePath,
                            staticConfig);
            LOGGER.info("Tia publish stamp for {} {}: {} (seq {}, {} methods).",
                    groupArtifact, publishedVersion, result.getOutcome(), result.getPublishSeq(),
                    result.getStampedMethodIds().size());
        }
    }

    /**
     * Resolve the file path of the archive this project's {@code jar} task produced, for
     * content-hashing into the ledger row. When no built archive is available the publish is
     * still recorded, with a null hash - the drain then identifies the build by exact version
     * for releases.
     *
     * @return the built jar's absolute path, or null when the jar task or its output is absent.
     */
    private String resolveBuiltArchivePath() {
        Task jarTask = project.getTasks().findByName("jar");
        if (jarTask instanceof AbstractArchiveTask) {
            File archive = ((AbstractArchiveTask) jarTask).getArchiveFile().get().getAsFile();
            if (archive.exists()) {
                return archive.getAbsolutePath();
            }
        }
        LOGGER.warn("No built jar archive found - the publish will be recorded without a jar hash.");
        return null;
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
     * @return the configured SQL dialect override from the {@code tia { ... }} extension, or
     *         {@code null} to infer the dialect from {@link #getDbUrl()}
     */
    public String getDbDialect() {
        return tiaTaskExtension.getDbDialect();
    }

    /**
     * Resolve the H2 connection settings for the daemon-side Tia tasks. Picks server mode when
     * {@code dbUrl} is configured, otherwise embedded mode using {@link #resolveDbFilePath()}
     * (which resolves a relative {@code dbFilePath} against the project dir, not the daemon cwd).
     *
     * @return the resolved embedded- or server-mode connection settings
     */
    public H2ConnectionSettings buildH2ConnectionSettings() {
        return H2ConnectionSettings.fromConfig(resolveDbFilePath(), getDbUrl(), getDbUser(),
                getDbPassword());
    }

    /**
     * Build the {@link DataStore} for the daemon-side Tia tasks, resolving the SQL dialect from
     * the {@code tia { ... }} extension's connection properties via {@link DataStoreFactory}.
     * Shares {@link #resolveDbFilePath()} with {@link #buildH2ConnectionSettings()} so both
     * paths agree on the daemon-cwd-vs-projectDir resolution described there.
     *
     * @param branch the VCS branch name, used to derive the per-branch schema selected on each
     *               connection
     * @return the constructed datastore for the resolved dialect
     */
    public DataStore buildDataStore(String branch) {
        return DataStoreFactory.fromConfig(resolveDbFilePath(), getDbUrl(), getDbUser(),
                getDbPassword(), getDbDialect(), branch);
    }

    /**
     * Daemon-side tasks ({@code tia-select-tests}, {@code tia-status}, {@code tia-text-report},
     * {@code tia-html-report}) construct JdbcDataStore directly in the Gradle daemon. The daemon's
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

    /**
     * @return whether this build participates in a distributed test run - the master switch
     *         stage 5's claim protocol branches on, on the test-task side
     */
    public Boolean getDistributed() {
        return tiaTaskExtension.getDistributed();
    }

    /**
     * @return the configured distributed run's shared identifier, or {@code null} if not
     *         configured
     */
    public String getRunId() {
        return tiaTaskExtension.getRunId();
    }

    /**
     * @return the configured fixed group count for a distributed run, or {@code null} to use a
     *         target run time instead
     */
    public Integer getDistributedGroupCount() {
        return tiaTaskExtension.getDistributedGroupCount();
    }

    /**
     * @return the configured target wall-clock run time in ms for a distributed run, or {@code
     *         null} to use a fixed group count instead
     */
    public Long getDistributedTargetRunTime() {
        return tiaTaskExtension.getDistributedTargetRunTime();
    }

    /**
     * @return the configured ceiling on the group count for a distributed run, or {@code null}
     *         for no ceiling
     */
    public Integer getDistributedMaxGroups() {
        return tiaTaskExtension.getDistributedMaxGroups();
    }

    /**
     * @return the configured per-runner identity value for a distributed run, or {@code null} to
     *         let the claim protocol derive one
     */
    public String getDistributedRunnerKey() {
        return tiaTaskExtension.getDistributedRunnerKey();
    }

    /**
     * Resolve the directory the {@code tia-dist-plan} task writes {@code tia-run-plan.json} under.
     * Defaults to {@code <project build dir>/tia} - the Gradle analog of the Maven goal's {@code
     * tiaBuildDir} default of {@code ${project.build.directory}/tia} - but is overridable via the
     * {@code tia { buildDir = ... } } extension property, the Gradle analog of Maven's {@code
     * -DtiaBuildDir=...}. This lever matters on a multi-project build where the plugin is applied
     * to a subproject: a CI pipeline that looks for the plan file at a fixed path needs to be able
     * to point it somewhere predictable, the same way the Maven side already can via {@code
     * tiaBuildDir}.
     *
     * @return the absolute path of the directory the distributed run plan file is written under
     */
    public String getTiaBuildDir() {
        String configured = tiaTaskExtension.getBuildDir();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        return project.getLayout().getBuildDirectory().getAsFile().get().getPath()
                + File.separator + "tia";
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
        if (libs == null || libs.trim().isEmpty()) {
            return new LibraryImpactAnalysisConfig(null, null, null, null);
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
        return new LibraryImpactAnalysisConfig(coordinates, libraryProjectDirs, getSourceProjectDir(), reader);
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
