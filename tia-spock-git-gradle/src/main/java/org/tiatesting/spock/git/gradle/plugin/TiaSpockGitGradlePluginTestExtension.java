package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.JavaForkOptions;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.slf4j.Logger;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunPreconditions;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.gradle.plugin.LibraryJarResolver;
import org.tiatesting.gradle.plugin.TiaBasePlugin;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;
import org.tiatesting.spock.library.LibraryMetadataSystemProperties;
import org.tiatesting.spock.library.PreResolvedLibraryMetadataReader;
import org.tiatesting.spock.staticselection.StaticTestSelectionSystemProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TiaSpockGitGradlePluginTestExtension {
    private static final Logger LOGGER = Logging.getLogger(TiaSpockGitGradlePluginTestExtension.class);

    public TiaSpockGitGradlePluginTestExtension(){
    }

    public <T extends Test & JavaForkOptions> void applyTo(final T task) {
        String taskName = task.getName();
        LOGGER.debug("Applying Tia to " + taskName);
        TiaBaseTaskExtension tiaProjectExtension = task.getProject().getExtensions().findByType(TiaBaseTaskExtension.class);
        TiaBaseTaskExtension tiaTaskExtension = task.getExtensions().create("tia", TiaBaseTaskExtension.class);
        JacocoTaskExtension jacocoTaskExtension = task.getExtensions().findByType(JacocoTaskExtension.class);

        Action<Task> action = new Action<Task>() {
            @Override
            public void execute(Task task) {
                Test testTask = (Test)task;
                populateTestTaskExtension(tiaProjectExtension, tiaTaskExtension);
                boolean isTiaEnabled = isEnabled(tiaTaskExtension, testTask);

                if (isTiaEnabled){
                    // set the system properties needed by Tia passed in as configuration from the Gradle plugin
                    testTask.systemProperty("tiaEnabled", true);
                    testTask.systemProperty("tiaUpdateDBMapping", tiaTaskExtension.getUpdateDBMapping());
                    testTask.systemProperty("tiaUpdateDBStats", tiaTaskExtension.getUpdateDBStats());
                    testTask.systemProperty("tiaUpdateDBTestRunHistory", tiaTaskExtension.getUpdateDBTestRunHistory());
                    testTask.systemProperty("tiaProjectDir", tiaTaskExtension.getProjectDir());
                    testTask.systemProperty("tiaClassFilesDirs", tiaTaskExtension.getClassFilesDirs());
                    testTask.systemProperty("tiaSourceFilesDirs", tiaTaskExtension.getSourceFilesDirs());
                    testTask.systemProperty("tiaTestFilesDirs", tiaTaskExtension.getTestFilesDirs());
                    testTask.systemProperty("tiaDBFilePath", tiaTaskExtension.getDbFilePath());
                    // Server-mode H2 connection settings. Forwarded only when set so that in the
                    // common embedded case the listener does not see the literal string "null"
                    // and mistake it for a server URL.
                    if (tiaTaskExtension.getDbUrl() != null){
                        testTask.systemProperty("tiaDBUrl", tiaTaskExtension.getDbUrl());
                    }
                    if (tiaTaskExtension.getDbUser() != null){
                        testTask.systemProperty("tiaDBUser", tiaTaskExtension.getDbUser());
                    }
                    if (tiaTaskExtension.getDbPassword() != null){
                        testTask.systemProperty("tiaDBPassword", tiaTaskExtension.getDbPassword());
                    }
                    if (tiaTaskExtension.getDbDialect() != null){
                        testTask.systemProperty("tiaDBDialect", tiaTaskExtension.getDbDialect());
                    }
                    testTask.systemProperty("tiaCheckLocalChanges", tiaTaskExtension.getCheckLocalChanges());

                    LibraryJarResolver resolver = new LibraryJarResolver(testTask.getProject(), LOGGER);
                    String libraryJarsCsv = resolver.resolveLibraryJarsCsv(
                            tiaTaskExtension.getSourceLibs(),
                            tiaTaskExtension.getSourceProjectDir());
                    if (libraryJarsCsv != null && !libraryJarsCsv.isEmpty()){
                        testTask.systemProperty("tiaLibraryJars", libraryJarsCsv);
                    }

                    forwardLibraryMetadata(testTask, tiaTaskExtension, resolver);
                    forwardStaticTestSelectionRules(testTask, tiaTaskExtension);
                    // Claims this test task's share of a distributed run right here in the
                    // daemon, before the test JVM forks - see claimDistributedRun for why the
                    // fork can no longer make this claim itself. The result is only used to
                    // forward the claim below; Task 2b's finalizer task is what needs to read it
                    // back after the test task's forked JVM(s) finish, and does not yet exist -
                    // see the "Distributed test runs" chapter in WIKI.md for where that lands.
                    DistributedRunnerAssignment distributedRunnerAssignment =
                            claimDistributedRun(testTask, tiaTaskExtension);

                    // only apply and configure the jacoco task extension if we're updating the tia DB
                    if (tiaTaskExtension.getUpdateDBMapping()) {
                        LOGGER.debug("Enabling Jacoco in TCP server mode");
                        jacocoTaskExtension.setEnabled(true);
                        jacocoTaskExtension.setOutput(JacocoTaskExtension.Output.TCP_SERVER);
                    }
                }else{
                    testTask.systemProperty("tiaEnabled", false);
                }
            }
        };

        task.doFirst(action);
    }

    /**
     * Override the task extension object properties with the project object extension.
     *
     * This allows the user to define the Tia configuration at the project level, and override it for each test task
     * configuration type like 'test' and 'integrationTest'.
     *
     * @param tiaProjectExt the project-level {@code tia { ... }} extension, the source of any value
     *                      the test task did not set for itself
     * @param tiaTaskExt the test task's own {@code tia { ... }} extension, populated in place
     */
    private void populateTestTaskExtension(TiaBaseTaskExtension tiaProjectExt, TiaBaseTaskExtension tiaTaskExt){
        if (tiaTaskExt.getEnabled() == null){
            tiaTaskExt.setEnabled(tiaProjectExt.getEnabled());
        }

        if (tiaTaskExt.getUpdateDBMapping() == null){
            tiaTaskExt.setUpdateDBMapping(tiaProjectExt.getUpdateDBMapping());
        }

        if (tiaTaskExt.getUpdateDBStats() == null){
            tiaTaskExt.setUpdateDBStats(tiaProjectExt.getUpdateDBStats());
        }

        if (tiaTaskExt.getUpdateDBTestRunHistory() == null){
            // Project extension may not have set it explicitly. Default to true so users
            // get the history log without having to opt in.
            tiaTaskExt.setUpdateDBTestRunHistory(
                    tiaProjectExt.getUpdateDBTestRunHistory() != null
                            ? tiaProjectExt.getUpdateDBTestRunHistory()
                            : Boolean.TRUE);
        }

        if (tiaTaskExt.getProjectDir() == null){
            tiaTaskExt.setProjectDir(tiaProjectExt.getProjectDir());
        }

        if (tiaTaskExt.getClassFilesDirs() == null){
            tiaTaskExt.setClassFilesDirs(tiaProjectExt.getClassFilesDirs());
        }

        if (tiaTaskExt.getSourceFilesDirs() == null){
            tiaTaskExt.setSourceFilesDirs(tiaProjectExt.getSourceFilesDirs());
        }

        if (tiaTaskExt.getTestFilesDirs() == null){
            tiaTaskExt.setTestFilesDirs(tiaProjectExt.getTestFilesDirs());
        }

        if (tiaTaskExt.getDbFilePath() == null){
            tiaTaskExt.setDbFilePath(tiaProjectExt.getDbFilePath());
        }

        if (tiaTaskExt.getDbUrl() == null){
            tiaTaskExt.setDbUrl(tiaProjectExt.getDbUrl());
        }

        if (tiaTaskExt.getDbUser() == null){
            tiaTaskExt.setDbUser(tiaProjectExt.getDbUser());
        }

        if (tiaTaskExt.getDbPassword() == null){
            tiaTaskExt.setDbPassword(tiaProjectExt.getDbPassword());
        }

        if (tiaTaskExt.getDbDialect() == null){
            tiaTaskExt.setDbDialect(tiaProjectExt.getDbDialect());
        }

        if (tiaTaskExt.getCheckLocalChanges() == null){
            tiaTaskExt.setCheckLocalChanges(tiaProjectExt.getCheckLocalChanges());
        }

        if (tiaTaskExt.getSourceLibs() == null){
            tiaTaskExt.setSourceLibs(tiaProjectExt.getSourceLibs());
        }

        if (tiaTaskExt.getSourceProjectDir() == null){
            tiaTaskExt.setSourceProjectDir(tiaProjectExt.getSourceProjectDir());
        }

        // A distributed run is configured per pipeline, not per test task: the run id and the
        // runner key identify this CI job, so they are almost always set once at the project level
        // (or on the command line) and must reach every test task that participates.
        if (tiaTaskExt.getDistributed() == null){
            tiaTaskExt.setDistributed(tiaProjectExt.getDistributed());
        }

        if (tiaTaskExt.getRunId() == null){
            tiaTaskExt.setRunId(tiaProjectExt.getRunId());
        }

        if (tiaTaskExt.getDistributedRunnerKey() == null){
            tiaTaskExt.setDistributedRunnerKey(tiaProjectExt.getDistributedRunnerKey());
        }
    }

    /**
     * Check if Tia is enabled. Used to determine if we should load the Tia agent and analyse the
     * changes and @Ignore tests not impacted by the changes.
     *
     * Note: It's not ideal we need to cast to the DefaultTestFilter as it's the internals of Gradle and
     * could change in future versions. Another way of getting the command line --tests parameter is using the
     * @Option(option = "tests", description = "Sets test class or method name to be included, '*' is supported.")
     * https://github.com/gradle/gradle/blob/b131fefc8d9efb8e154abd09f7eb91c854df1310/subprojects/testing-base/src/main/java/org/gradle/api/tasks/testing/AbstractTestTask.java#L104
     * annotation. But again this is currently only intended for the internals of Gradle.
     * i.e. Gradle doesn't currently provide a good way to publicly expose the command line parameters.
     *
     * @param tiaTaskExtension
     * @param task
     * @return
     */
    private boolean isEnabled(final TiaBaseTaskExtension tiaTaskExtension, Test task){
        boolean enabled = tiaTaskExtension.getEnabled() != null ? tiaTaskExtension.getEnabled() : false;
        boolean updateDBMapping = tiaTaskExtension.getUpdateDBMapping() != null ? tiaTaskExtension.getUpdateDBMapping() : false;
        boolean updateDBStats = tiaTaskExtension.getUpdateDBStats() != null ? tiaTaskExtension.getUpdateDBStats() : false;
        boolean updateDBTestRunHistory = tiaTaskExtension.getUpdateDBTestRunHistory() != null
                ? tiaTaskExtension.getUpdateDBTestRunHistory() : true;
        LOGGER.warn("Tia plugin task ext: enabled: " + enabled + ", update mapping: " + updateDBMapping
                + ", update stats: " + updateDBStats
                + ", update test run history: " + updateDBTestRunHistory);

        /**
         * If the user specified specific individual tests to run, disable Tia so those tests are run
         * and guaranteed to be the only tests to run.
         */
        if (enabled){
            Set<String> userSpecifiedTests = ((DefaultTestFilter)task.getFilter()).getCommandLineIncludePatterns();
            boolean hasUserSpecifiedTests = userSpecifiedTests != null && !userSpecifiedTests.isEmpty();

            if (hasUserSpecifiedTests){
                LOGGER.info("Users has specified tests, disabling Tia");
                enabled = false;
            }
        }

        return enabled;
    }

    /**
     * Pre-resolve library metadata on the Gradle side and forward it to the test JVM as flat
     * system properties. The test JVM uses {@link LibraryMetadataSystemProperties} to rebuild a
     * {@code LibraryImpactAnalysisConfig} so {@code TestSelector} can run reconcile / partition /
     * stamp / drain in-process — without needing a Gradle {@code Project} reference.
     *
     * <p>Why pre-resolve here: {@link LibraryJarResolver} requires either the current Gradle
     * {@code Project} or a Tooling-API connection. Neither is available inside the forked test JVM.
     * The plugin runs the resolver once at task-action time and forwards the results.
     *
     * <p>The {@code tiaLibraryJars} CSV (set above) is a separate concern — it feeds JaCoCo so
     * library classes are included in coverage. The metadata forwarded here drives TIA's selection
     * logic.
     */
    private void forwardLibraryMetadata(Test testTask, TiaBaseTaskExtension tiaTaskExtension,
                                        LibraryJarResolver resolver) {
        String sourceLibs = tiaTaskExtension.getSourceLibs();
        if (sourceLibs == null || sourceLibs.trim().isEmpty()) {
            return;
        }

        List<CoordinateAndDir> parsed = parseSourceLibs(sourceLibs);
        if (parsed.isEmpty()) {
            return;
        }

        String sourceProjectDir = tiaTaskExtension.getSourceProjectDir();
        List<PreResolvedLibraryMetadataReader.Entry> entries = new ArrayList<>(parsed.size());

        for (CoordinateAndDir cd : parsed) {
            List<String> coordSingleton = Collections.singletonList(cd.coordinate);

            String declaredVersion = null;
            List<String> sourceDirs = Collections.emptyList();
            if (cd.projectDir != null && !cd.projectDir.isEmpty()) {
                List<LibraryBuildMetadata> metadata = resolver.readLibraryBuildMetadata(cd.projectDir, coordSingleton);
                if (!metadata.isEmpty()) {
                    declaredVersion = metadata.get(0).getDeclaredVersion();
                }
                sourceDirs = resolver.readSourceDirectories(cd.projectDir);
            }

            String resolvedVersion = null;
            String resolvedJar = null;
            List<ResolvedSourceProjectLibrary> resolved =
                    resolver.resolveLibrariesInSourceProject(sourceProjectDir, coordSingleton);
            if (!resolved.isEmpty()) {
                resolvedVersion = resolved.get(0).getResolvedVersion();
                resolvedJar = resolved.get(0).getJarFilePath();
            }

            entries.add(new PreResolvedLibraryMetadataReader.Entry(
                    cd.coordinate, cd.projectDir, declaredVersion, sourceDirs, resolvedVersion, resolvedJar));
        }

        String encoded = LibraryMetadataSystemProperties.formatEntries(entries);
        if (!encoded.isEmpty()) {
            testTask.systemProperty(LibraryMetadataSystemProperties.PROP_LIBRARIES_METADATA, encoded);
        }
        if (sourceProjectDir != null && !sourceProjectDir.isEmpty()) {
            testTask.systemProperty(LibraryMetadataSystemProperties.PROP_SOURCE_PROJECT_DIR, sourceProjectDir);
        }
    }

    /**
     * Build the user's static test selection rules into a {@link StaticTestSelectionConfig} on
     * the Gradle side and forward the encoded form to the forked test JVM as a single system
     * property. The test JVM uses
     * {@link StaticTestSelectionSystemProperties#fromSystemProperties()} to rebuild the config
     * so {@code TestSelector} can apply the rules in-process.
     *
     * <p>Building the config on the Gradle side surfaces invalid regex / unknown mode errors
     * at configuration time rather than deferring them to the forked test JVM.
     *
     * @param testTask the test task whose forked JVM receives the property.
     * @param tiaTaskExtension the Tia extension carrying the user-declared rule list.
     */
    private void forwardStaticTestSelectionRules(Test testTask, TiaBaseTaskExtension tiaTaskExtension) {
        StaticTestSelectionConfig config = TiaBasePlugin.buildStaticTestSelectionConfig(
                tiaTaskExtension.getStaticTestSelectionRules());
        if (!config.isEnabled()) {
            return;
        }
        String encoded = StaticTestSelectionSystemProperties.format(config);
        if (!encoded.isEmpty()) {
            testTask.systemProperty(StaticTestSelectionSystemProperties.PROP_STATIC_TEST_SELECTION_RULES, encoded);
        }
    }

    /**
     * Claim this test task's share of a distributed run in the daemon, at task-action time, before
     * the test JVM forks - and forward only the claim's result to that JVM.
     *
     * <p>Gradle used to claim inside the forked test JVM instead ({@code
     * TiaSpockTestRunInitializer#claimDistributedRunGroup}, now removed), because that was the
     * only place stage 5 had a claim protocol to call from. That made Gradle claim once per forked
     * test JVM rather than once per test task - a build with {@code maxParallelForks > 1} could
     * claim several groups for what is meant to be a single runner - and left no daemon-side
     * record of which group a task's JVM held, which is what a later daemon-side "this group is
     * finished" step (stage 9's task 2b, mirroring the Maven {@code tia-dist-complete} goal) needs
     * to read back. Claiming here fixes both: one claim per test task, and a result the daemon
     * itself can see.
     *
     * <p>Resolves the {@link TiaBasePlugin} applied to this project for its datastore and VCS
     * reader, since neither is available from the task extension alone. Looked up via {@link
     * org.gradle.api.plugins.PluginCollection#withType(Class)}, not {@code
     * PluginContainer#findPlugin(Class)}: {@code findPlugin} matches a plugin's exact registered
     * class, never a supertype, so it can never find the concrete {@code TiaSpockGitGradlePlugin}
     * (or any other {@link TiaBasePlugin} subclass) a real project actually has applied - only
     * {@code withType} does assignability-based matching.
     *
     * <p>Then enforces {@link DistributedRunPreconditions#check} with this build's real reactor
     * size. The forked test JVM used to enforce three of those four rules itself and always passed
     * a literal {@code 1} for the fourth (the reactor-size rule), because it had no {@code Project}
     * reference at all to count projects with - planning already refused any multi-project Gradle
     * build, so that literal was never reachable as a gap, just unable to add a second layer of
     * defence. This action runs in the daemon with {@code task.getProject()} available, so it can
     * and does pass the real count, catching a multi-project reactor at claim time as well as at
     * plan time.
     *
     * <p>{@link DistributedRunConfig#forRunner} builds the claim's configuration - a runner
     * configures only the run it belongs to and who it is, never a group count or a target run
     * time, since that shape is the planning job's decision and is already recorded in the plan
     * being claimed from. The claim itself is {@link DistributedRunnerAssignment#claim}, the same
     * method the Maven {@code prepare-agent} goal calls, so a Maven and a Gradle runner cannot
     * disagree by even one suite about which suites a group owns. Only the resolved run id, runner
     * key and group number are forwarded, via {@link DistributedForkProperties#forkProperties} -
     * the exact property set and rendering Maven already writes to {@code fork.properties} - so
     * {@link DistributedForkProperties#contextFromSystemProperties()} resolves the same context on
     * either build tool. The suite lists themselves are not forwarded: they can be large, and the
     * fork reads them from the shared database instead.
     *
     * @param testTask the test task whose forked JVM receives the claimed run id, runner key and
     *                 group number
     * @param tiaTaskExtension the test task's own resolved Tia extension - already merged with the
     *                         project-level extension by {@link #populateTestTaskExtension} - which
     *                         carries the distributed master switch, the run id and the configured
     *                         runner key
     * @return this test task's claimed assignment, or null when this build is not a distributed
     *         runner (nothing is forwarded to the fork in that case, either)
     * @throws IllegalStateException if the distributed-run preconditions fail (Tia disabled, a
     *                                multi-project reactor, an embedded database, or local-changes
     *                                checking enabled), or if no run is planned under the
     *                                configured run id, or if the plan was built against a
     *                                different commit than this workspace is on - all of which must
     *                                fail this test task's build rather than let it start a forked
     *                                JVM with no claim to run against
     */
    private DistributedRunnerAssignment claimDistributedRun(Test testTask, TiaBaseTaskExtension tiaTaskExtension) {
        if (!Boolean.TRUE.equals(tiaTaskExtension.getDistributed())) {
            return null;
        }

        // withType, not findPlugin: findPlugin(Class) only matches a plugin's exact registered
        // class and would never find the concrete TiaSpockGitGradlePlugin instance this project
        // actually has applied, since it is a TiaBasePlugin subclass rather than a TiaBasePlugin
        // itself. withType does assignability-based matching and finds it correctly.
        TiaBasePlugin plugin = testTask.getProject().getPlugins().withType(TiaBasePlugin.class)
                .stream().findFirst().orElse(null);
        if (plugin == null) {
            throw new IllegalStateException("Tia distributed test runs require the Tia Gradle "
                    + "plugin (a " + TiaBasePlugin.class.getName() + ") to be applied to project '"
                    + testTask.getProject().getPath() + "' - the claim needs its datastore and VCS "
                    + "reader, and none was found.");
        }

        // Checked here, in the daemon, against this build's real project count - see this
        // method's javadoc for why the old test-JVM claim could only ever pass a literal 1.
        // tiaEnabled is already true whenever this method runs: the caller only reaches it inside
        // applyTo's isTiaEnabled branch.
        DistributedRunPreconditions.check(true, plugin.getReactorProjects().size(),
                plugin.getDbUrl(), plugin.getDbDialect(),
                Boolean.TRUE.equals(tiaTaskExtension.getCheckLocalChanges()));

        DistributedRunConfig config = DistributedRunConfig.forRunner(tiaTaskExtension.getRunId(),
                tiaTaskExtension.getDistributedRunnerKey());
        VCSReader vcsReader = plugin.getVCSReader();
        DistributedRunnerAssignment assignment;
        // try-with-resources: this connection is only needed long enough to make the claim: it
        // must not stay open for the rest of the build, since nothing else this daemon-side action
        // does touches the datastore, and holding a shared-database connection open across the
        // whole test run would tie up a resource none of that work needs.
        try (DataStore dataStore = plugin.buildDataStore(vcsReader.getBranchName())) {
            assignment = DistributedRunnerAssignment.claim(dataStore, config, vcsReader.getHeadCommit(),
                    System.currentTimeMillis());
        }

        if (assignment.isClaimed()) {
            LOGGER.info("Tia distributed run '{}': test task '{}' claimed group {} and will run {} "
                            + "test suite(s).", config.getRunId(), testTask.getPath(),
                    assignment.getGroupNumber(), assignment.getTestsToRun().size());
        } else {
            LOGGER.info("Tia distributed run '{}': test task '{}' claimed no group - every group "
                            + "was already claimed, so this test task will run no tests. This is "
                            + "expected when the pipeline fans out to more jobs than the plan has "
                            + "groups.", config.getRunId(), testTask.getPath());
        }

        Map<String, String> properties = DistributedForkProperties.forkProperties(config.getRunId(),
                assignment.getRunnerKey(), assignment.getGroupNumber());
        for (Map.Entry<String, String> property : properties.entrySet()) {
            testTask.systemProperty(property.getKey(), property.getValue());
        }

        return assignment;
    }

    /**
     * Parse the user-facing {@code sourceLibs} CSV into {@code (coordinate, projectDir)} pairs.
     * Accepts both {@code groupId:artifactId} and {@code groupId:artifactId:projectDir} forms,
     * matching {@link org.tiatesting.gradle.plugin.TiaBasePlugin#buildLibraryImpactAnalysisConfig()}.
     */
    private List<CoordinateAndDir> parseSourceLibs(String sourceLibs) {
        List<CoordinateAndDir> result = new ArrayList<>();
        for (String raw : sourceLibs.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] segments = entry.split(":");
            if (segments.length == 3) {
                result.add(new CoordinateAndDir(
                        segments[0].trim() + ":" + segments[1].trim(),
                        segments[2].trim()));
            } else if (segments.length == 2) {
                result.add(new CoordinateAndDir(entry, null));
            } else {
                LOGGER.warn("Invalid sourceLibs entry '{}' - expected groupId:artifactId or "
                        + "groupId:artifactId:projectDir, skipping.", entry);
            }
        }
        return result;
    }

    private static final class CoordinateAndDir {
        final String coordinate;
        final String projectDir;

        CoordinateAndDir(String coordinate, String projectDir) {
            this.coordinate = coordinate;
            this.projectDir = projectDir;
        }
    }
}
