package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.JavaForkOptions;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.slf4j.Logger;
import org.tiatesting.core.distributed.ClaimOutcome;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunCoordinator;
import org.tiatesting.core.distributed.DistributedRunPreconditions;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.testrunner.RunEnvironment;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.gradle.plugin.DistributedClaimRegistry;
import org.tiatesting.gradle.plugin.LibraryJarResolver;
import org.tiatesting.gradle.plugin.TiaBasePlugin;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;
import org.tiatesting.gradle.plugin.TiaDistCompleteTask;
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

        // Wired at configuration time, not inside the doFirst action below: the task graph (and
        // therefore any finalizedBy wiring) is built before execution, so the finalizer must exist
        // before the doFirst action runs. See wireDistCompleteFinalizer for why this needs its own,
        // narrower resolution of the "distributed" flag rather than reusing populateTestTaskExtension.
        //
        // Depends on this method being reached while the project is still evaluating, because
        // Project.afterEvaluate throws InvalidUserCodeException once evaluation is over. That holds
        // today only because TiaSpockGitGradlePlugin.apply iterates project.getTasks().withType(
        // Test.class) with a plain for-loop before it wires applyToDefaultTasks, which realizes
        // every Test task during evaluation and so makes the configureEach action behind this call
        // run then rather than later. Making that iteration lazy, or a Test task registered by a
        // plugin applied after Tia, would push this past evaluation and break the build. The
        // ProjectBuilder tests create their tasks eagerly and cannot catch it.
        task.getProject().afterEvaluate(p -> wireDistCompleteFinalizer(task, tiaProjectExtension, tiaTaskExtension));

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
                    // Forwarded only when declared. Unset means "let Tia detect it" - forwarding the
                    // literal string "null" would be stored verbatim as the run's source.
                    if (tiaTaskExtension.getRunSource() != null){
                        testTask.systemProperty(RunEnvironment.PROP_RUN_SOURCE, tiaTaskExtension.getRunSource());
                    }

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
                    // fork can no longer make this claim itself. The claim is recorded in the
                    // build's DistributedClaimRegistry as a side effect; the tia-dist-complete
                    // finalizer reads it back from there after the test task's forked JVM(s)
                    // finish - see the "Distributed test runs" chapter in WIKI.md.
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

        // Like the distributed settings below, the run source describes the build rather than any
        // one test task, so it is normally declared once at the project level and must reach every
        // test task from there.
        if (tiaTaskExt.getRunSource() == null){
            tiaTaskExt.setRunSource(tiaProjectExt.getRunSource());
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
     * Register the {@code tia-dist-complete} finalizer for this test task, and wire it as {@code
     * testTask.finalizedBy(...)}, but only when Tia is enabled and this test task is configured for
     * a distributed run - an ordinary, non-distributed Gradle build must gain no task and no
     * finalizer, and neither must a build that has Tia switched off, whatever else it configures.
     *
     * <p>Called from {@code project.afterEvaluate}, after the user's build script has finished
     * setting both the project-level and this task's own {@code tia { ... } } extension, since the
     * task graph - and therefore any {@code finalizedBy} wiring - is built before execution, while
     * {@link #populateTestTaskExtension} only merges the two extensions inside the test task's
     * {@code doFirst} action, which runs too late to affect the task graph. Rather than duplicate
     * that whole merge this early, only the two flags a finalizer decision needs - {@code enabled}
     * and {@code distributed} - are resolved here, with {@link #populateTestTaskExtension}'s same
     * "task extension wins, project extension is the fallback" rule.
     *
     * <p>Resolves the {@link TiaBasePlugin} applied to this project the same way {@link
     * #claimDistributedRun} does, via {@code withType} rather than {@code findPlugin}. Finding none
     * is not escalated here: with no plugin applied, {@link #claimDistributedRun} will already fail
     * this build with a clear error the first time the test task's {@code doFirst} action actually
     * attempts a claim, so failing a second time from this configuration-time hook would only
     * duplicate that message.
     *
     * @param testTask the test task to finalize with a {@code tia-dist-complete} task, if this
     *                  build turns out to be distributed
     * @param tiaProjectExtension the project-level {@code tia { ... } } extension
     * @param tiaTaskExtension the test task's own {@code tia { ... } } extension
     */
    private void wireDistCompleteFinalizer(final Test testTask, final TiaBaseTaskExtension tiaProjectExtension,
                                           final TiaBaseTaskExtension tiaTaskExtension) {
        // Disabled Tia is inert, as it is on the Maven side, where
        // AbstractTiaDistCompleteMojo.execute short-circuits on !isTiaEnabled() as its very first
        // statement. Not quite the same gate - see resolveFlagAtConfigurationTime for the one case
        // (--tests) this cannot see this early, and why it is harmless. Without this, a build with
        // tia.distributed = true and Tia switched off would
        // still register tia-dist-complete, still finalize the test task with it, and - with two
        // distributed test tasks - still fail at configuration time on a guard for a run it was
        // never going to make.
        if (!resolveFlagAtConfigurationTime(tiaTaskExtension.getEnabled(), tiaProjectExtension.getEnabled())) {
            return;
        }

        if (!resolveFlagAtConfigurationTime(tiaTaskExtension.getDistributed(),
                tiaProjectExtension.getDistributed())) {
            return;
        }

        TiaBasePlugin plugin = testTask.getProject().getPlugins().withType(TiaBasePlugin.class)
                .stream().findFirst().orElse(null);
        if (plugin == null) {
            return;
        }

        if (testTask.getProject().getTasks().getNames().contains(TiaBasePlugin.DIST_COMPLETE_TASK_NAME)) {
            // A second distributed test task in the same build. Registering the finalizer again
            // would fail with Gradle's own "a task with that name already exists" message, which
            // says nothing about why two distributed test tasks cannot work - the same reason
            // DistributedClaimRegistry.recordClaim refuses the second claim, only reached at
            // execution time, after this configuration-time registration would already have failed.
            // Checked against the task names rather than by looking the task up, so an ordinary
            // build never realizes a task just to find out it is absent.
            throw new GradleException("Test task '" + testTask.getPath() + "' is configured for a "
                    + "distributed test run, but another test task in this build already is. A "
                    + "distributed run supports exactly one test task per runner: the plan groups "
                    + "suites across the whole project, so a second test task's group could hold "
                    + "suites the first task cannot run, the completeness guard would never be "
                    + "satisfied, and the run would never seal. Configure only one test task as "
                    + "distributed per runner - run the other test task's share of the plan as a "
                    + "separate runner (a separate CI job/process) instead.");
        }

        TaskProvider<TiaDistCompleteTask> completeTask = plugin.createDistCompleteTask(testTask.getPath());
        testTask.finalizedBy(completeTask);
    }

    /**
     * Resolve one of the Tia extension's boolean flags the same way {@link
     * #populateTestTaskExtension} would, but standalone and safe to call at configuration time:
     * this test task's own value if it set one, otherwise the project-level extension's value.
     *
     * <p>Used for both flags a finalizer decision needs - {@code enabled} and {@code distributed} -
     * rather than duplicating the merge rule per flag, so the two cannot drift apart from each
     * other or from {@link #populateTestTaskExtension}.
     *
     * <p>For {@code enabled} this is narrower than {@link #isEnabled}, which additionally switches
     * Tia off when the user passes {@code --tests} - a decision that cannot be made this early,
     * since the finalizer wiring happens before the task's filter is read. So {@code ./gradlew test
     * --tests Foo} on a distributed build still registers and wires {@code tia-dist-complete}, which
     * is a wider gate than Maven's {@code !isTiaEnabled()} short-circuit, not an equal one. It is
     * harmless: no claim is made, so the finalizer finds no {@link DistributedClaimRegistry} entry
     * for the test task and exits at its no-claim branch without touching the datastore.
     *
     * @param taskValue the test task's own value for the flag, which wins when set
     * @param projectValue the project-level extension's value for the flag, the fallback
     * @return true only when the resolved value is {@link Boolean#TRUE}
     */
    private boolean resolveFlagAtConfigurationTime(final Boolean taskValue, final Boolean projectValue) {
        return Boolean.TRUE.equals(taskValue != null ? taskValue : projectValue);
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
        boolean updateDBTestRunHistory = tiaTaskExtension.getUpdateDBTestRunHistory() != null
                ? tiaTaskExtension.getUpdateDBTestRunHistory() : true;
LOGGER.warn("Tia plugin task ext: enabled: " + enabled + ", update mapping (and stats): " + updateDBMapping
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
     * stamp / drain in-process - without needing a Gradle {@code Project} reference.
     *
     * <p>Why pre-resolve here: {@link LibraryJarResolver} requires either the current Gradle
     * {@code Project} or a Tooling-API connection. Neither is available inside the forked test JVM.
     * The plugin runs the resolver once at task-action time and forwards the results.
     *
     * <p>The {@code tiaLibraryJars} CSV (set above) is a separate concern - it feeds JaCoCo so
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
     * only place the claim protocol could be called from at the time. That made Gradle claim once
     * per forked test JVM rather than once per test task - a build with {@code maxParallelForks > 1} could
     * claim several groups for what is meant to be a single runner - and left no daemon-side
     * record of which group a task's JVM held, which is what a later daemon-side "this group is
     * finished" step ({@code tia-dist-complete}, mirroring the Maven {@code dist-complete} goal) needs
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
     * being claimed from. The claim itself is made through {@link DistributedRunCoordinator#claim}
     * directly rather than through {@link org.tiatesting.core.distributed.DistributedRunnerAssignment#claim},
     * the same coordinator method the Maven {@code prepare-agent} goal calls, so a Maven and a
     * Gradle runner cannot disagree by even one suite about which suites a group owns - but this
     * daemon-side caller stops at the coordinator's {@link ClaimOutcome} rather than going on to
     * derive the two suite lists {@code DistributedRunnerAssignment} would: nothing here reads
     * them, the fork derives them for itself from the forwarded run id, runner key and group
     * number, and deriving a copy that is immediately discarded would be work with no consumer.
     * Only the resolved run id, runner key and group number are forwarded, via {@link
     * DistributedForkProperties#forkProperties} - the exact property set and rendering Maven
     * already writes to {@code fork.properties} - so {@link
     * DistributedForkProperties#contextFromSystemProperties()} resolves the same context on either
     * build tool. The suite lists themselves are not forwarded: they can be large, and the fork
     * reads them from the shared database instead.
     *
     * <p>The claim is also recorded in this build's {@link DistributedClaimRegistry}, keyed by
     * this test task's path. A second test task attempting a claim in the same build finds that
     * entry and fails loudly - splitting a runner across two test tasks cannot be made to work, see
     * {@link DistributedClaimRegistry#recordClaim} for why - rather than the two test tasks'
     * claims silently colliding, one group being claimed twice and another left {@code PENDING}
     * forever with the run never sealing and nothing telling the user why.
     *
     * @param testTask the test task whose forked JVM receives the claimed run id, runner key and
     *                 group number
     * @param tiaTaskExtension the test task's own resolved Tia extension - already merged with the
     *                         project-level extension by {@link #populateTestTaskExtension} - which
     *                         carries the distributed master switch, the run id, the configured
     *                         runner key and the update-DB flags the registry records for the finalizer
     * @return this test task's recorded claim, or null when this build is not a distributed runner
     *         (nothing is forwarded to the fork or recorded in the registry in that case, either)
     * @throws IllegalStateException if the distributed-run preconditions fail (Tia disabled, a
     *                                multi-project reactor, an embedded database, or local-changes
     *                                checking enabled), if this test task would run its group in
     *                                more than one JVM (see {@link
     *                                #refuseATestTaskThatForksMoreThanOneJvm}), if no run is planned
     *                                under the configured
     *                                run id, if the plan was built against a different commit than
     *                                this workspace is on, or if a different test task already
     *                                claimed in this build - all of which must fail this test
     *                                task's build rather than let it start a forked JVM with no
     *                                claim to run against, or with a claim that collides with
     *                                another test task's
     */
    private DistributedClaimRegistry.Claim claimDistributedRun(Test testTask, TiaBaseTaskExtension tiaTaskExtension) {
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
        refuseATestTaskThatForksMoreThanOneJvm(testTask);

        DistributedRunConfig config = DistributedRunConfig.forRunner(tiaTaskExtension.getRunId(),
                tiaTaskExtension.getDistributedRunnerKey());
        VCSReader vcsReader = plugin.getVCSReader();
        ClaimOutcome outcome;
        // try-with-resources: this connection is only needed long enough to make the claim: it
        // must not stay open for the rest of the build, since nothing else this daemon-side action
        // does touches the datastore, and holding a shared-database connection open across the
        // whole test run would tie up a resource none of that work needs.
        try (DataStore dataStore = plugin.buildDataStore(vcsReader.getBranchName())) {
            outcome = new DistributedRunCoordinator(dataStore, config)
                    .claim(vcsReader.getHeadCommit(), System.currentTimeMillis());
        }

        Integer groupNumber = outcome.isClaimed()
                ? Integer.valueOf(outcome.getGroup().getGroupNumber()) : null;

        if (outcome.isClaimed()) {
            LOGGER.info("Tia distributed run '{}': test task '{}' claimed group {}.",
                    config.getRunId(), testTask.getPath(), groupNumber);
        } else {
            LOGGER.info("Tia distributed run '{}': test task '{}' claimed no group - every group "
                            + "was already claimed, so this test task will run no tests. This is "
                            + "expected when the pipeline fans out to more jobs than the plan has "
                            + "groups.", config.getRunId(), testTask.getPath());
        }

        Map<String, String> properties = DistributedForkProperties.forkProperties(config.getRunId(),
                outcome.getRunnerKey(), groupNumber);
        for (Map.Entry<String, String> property : properties.entrySet()) {
            testTask.systemProperty(property.getKey(), property.getValue());
        }

        DistributedClaimRegistry registry =
                DistributedClaimRegistry.forBuild(testTask.getProject().getGradle());
        return registry.recordClaim(testTask.getPath(), config.getRunId(), outcome.getRunnerKey(),
                groupNumber, Boolean.TRUE.equals(tiaTaskExtension.getUpdateDBMapping()),
                Boolean.TRUE.equals(tiaTaskExtension.getUpdateDBTestRunHistory()));
    }

    /**
     * Refuse a distributed test task that would run its group in more than one JVM, rather than let
     * the run hang, for a failure that is even less legible without the refusal.
     *
     * <p>Called from {@link #claimDistributedRun}, which runs in the test task's own {@code doFirst}
     * action - so this fails the build when the test task <b>starts</b>, not at configuration time
     * like {@link #wireDistCompleteFinalizer}'s two-distributed-test-tasks guard. That is the right
     * moment for this particular check rather than merely the convenient one: it reads the final
     * value of {@code maxParallelForks} and {@code forkEvery}, after everything that configures the
     * task has had its say, and it still fails before a single worker JVM is forked.
     *
     * <p>{@code suites_observed} - the figure the completion's completeness guard reads - depends on
     * one JVM working one group end to end, because it is written as {@code GREATEST(stored, value)}
     * over a set that is only cumulative within a single JVM. See {@link
     * DataStore#reportGroupProgress} for that contract. Both {@code maxParallelForks > 1} and
     * {@code forkEvery > 0} break it here: {@link #claimDistributedRun} claims once, in the daemon,
     * and forwards the one run id, runner key and group number as system properties, which Gradle
     * hands to every worker JVM. Worse, Gradle really does split the group's suites across those
     * workers, so no single worker ever observes the whole group: {@code GREATEST} settles on the
     * largest worker's count, strictly less than the group's assigned total, the guard never passes,
     * the group never completes and the run never seals. Nothing fails while that happens - the
     * completion is a no-op both build tools treat as normal - so the user would get a green build
     * and a Tia database that silently stopped advancing.
     *
     * <p>The check is exact rather than a heuristic: this action runs in the daemon, which holds the
     * {@link Test} task, so it reads the two settings straight off it. Nothing beyond those two
     * properties is inspected.
     *
     * @param testTask the distributed test task to check the forking settings of
     * @throws IllegalStateException if the test task sets {@code maxParallelForks} above one or
     *                                {@code forkEvery} above zero
     */
    private void refuseATestTaskThatForksMoreThanOneJvm(final Test testTask) {
        String forkingSetting = null;
        if (testTask.getMaxParallelForks() > 1) {
            forkingSetting = "maxParallelForks = " + testTask.getMaxParallelForks();
        } else if (testTask.getForkEvery() > 0) {
            forkingSetting = "forkEvery = " + testTask.getForkEvery();
        }

        if (forkingSetting == null) {
            return;
        }

        throw new IllegalStateException("Test task '" + testTask.getPath() + "' is configured for a "
                + "distributed test run, but also sets " + forkingSetting + ". A distributed run "
                + "needs one JVM per group: the group is claimed once here in the daemon and its "
                + "run id, runner key and group number are forwarded to every worker JVM Gradle "
                + "starts, while Gradle splits the group's suites across those workers - so no "
                + "single worker ever observes the whole group, the completion's suites-observed "
                + "guard would never be satisfied, the group would never complete and the run would "
                + "never seal. Remove that setting from this test task and take the parallelism from "
                + "the plan instead - more CI jobs, each one runner claiming one group.");
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
