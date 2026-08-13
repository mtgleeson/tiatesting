package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.agent.CommandLineSupport;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunPreconditions;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.library.LibraryImpactDrainResultSerializer;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static java.lang.String.format;

public abstract class AbstractTiaAgentMojo extends AbstractTiaMojo {

    /**
     * Name of the property used in maven-surefire-plugin.
     */
    static final String SUREFIRE_ARG_LINE = "argLine";
    private static final String IGNORED_TESTS_FILENAME = "ignored-tests.txt";
    private static final String SELECTED_TESTS_FILENAME = "selected-tests.txt";
    private static final String LIBRARY_JARS_FILENAME = "library-jars.txt";
    private static final String DRAIN_RESULT_FILENAME = "drain-result.ser";
    private static final String FORK_PROPERTIES_FILENAME = "fork.properties";

    /**
     * Allows to specify a property which will contains settings for JaCoCo Agent.
     * If not specified, then "argLine" would be used for "jar" packaging and
     * "tycho.testArgLine" for "eclipse-test-plugin".
     */
    @Parameter(property = "jacoco.propertyName")
    String propertyName;

    /**
     * Prepare the forked test JVM: work out which test suites it must skip and which it must run,
     * write those lists and the properties the Tia agent republishes in the fork, and add the agent
     * to the surefire {@code argLine}.
     *
     * <p>How the two suite lists are arrived at is the one thing that differs between an ordinary
     * and a distributed build. An ordinary build runs the test selection here. A distributed build
     * must not: the plan produced by {@code tia-dist-plan} already ran the VCS diff, the static
     * rules and the library-impact drain once, for every runner, and its output is in the shared
     * database. So a distributed build claims a group from that plan instead - see
     * {@link #claimDistributedRunGroup()}.
     *
     * @throws MojoExecutionException if a distributed runner cannot claim its share of the planned
     *                                run - it fails the build rather than continue, since a runner
     *                                that cannot tell whether its tests ran must never report green
     * @throws MojoFailureException never thrown directly; declared by the mojo contract
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!isEnabled()){
            return;
        }

        final String name = getEffectivePropertyName();
        final Properties projectProperties = getProject().getProperties();
        final String oldValue = projectProperties.getProperty(name);

        String libraryJarsFile = writeLibraryJarsFile();

        Set<String> testsToIgnore;
        Set<String> testsToRun;
        LibraryImpactDrainResult drainResult;
        DistributedRunnerAssignment assignment = null;

        if (isTiaDistributed()){
            // A distributed runner claims its share of an existing plan instead of selecting. The
            // plan already ran the diff and the library-impact drain once; repeating the drain
            // per-runner would race, and its cleanup belongs to the run's sealer, so no drain
            // result is written here.
            assignment = claimDistributedRunGroup();
            testsToIgnore = assignment.getTestsToIgnore();
            testsToRun = assignment.getTestsToRun();
            drainResult = null;
        } else {
            TestSelectorResult testSelectorResult = getTestSelectorResult();
            testsToIgnore = testSelectorResult.getTestsToIgnore();
            testsToRun = testSelectorResult.getTestsToRun();
            drainResult = testSelectorResult.getLibraryImpactDrainResult();
        }

        String forkPropertiesFile = writeForkPropertiesFile(assignment);
        writeIgnoredTestsToFile(testsToIgnore);
        writeSelectedTestsToFile(testsToRun);
        String drainResultFile = writeDrainResultFile(drainResult);

        final AgentOptions agentOptions = buildTiaAgentOptions(libraryJarsFile, drainResultFile, forkPropertiesFile);
        final String newValue = addVMArguments(oldValue, getAgentJarFile(), agentOptions);
        getLog().info(name + " set to " + newValue);
        projectProperties.setProperty(name, newValue);

        // trying to configure the surefire plugin programtically below to work for tia doesn't seem to work
        // I can update the configuration for the surefire plugin but the change don't seem to get read.
        // https://users.maven.apache.narkive.com/QhDCjYKK/maven-3-no-longer-supporting-dynamic-configuration-of-plugins
        /*
        Optional<Plugin> surefirePlugin = project.getBuildPlugins().stream()
                .filter(plugin ->
                    ((Plugin)plugin).getGroupId().equals("org.apache.maven.plugins") &&
                            ((Plugin) plugin).getArtifactId().equals("maven-surefire-plugin") )
                .findFirst();
        getLog().info(surefirePlugin.get().getConfiguration().toString());

        Xpp3Dom configuration = (Xpp3Dom)surefirePlugin.get().getConfiguration();
        Xpp3Dom systemPropertyVariables = configuration.getChild("systemPropertyVariables");

        Xpp3Dom tiaProjectDirXpp3Dom = new Xpp3Dom("tiaProjectDir");
        tiaProjectDirXpp3Dom.setValue(tiaProjectDir);
        systemPropertyVariables.addChild(tiaProjectDirXpp3Dom);

        getLog().info(surefirePlugin.get().getConfiguration().toString());
         */
    }

    private TestSelectorResult getTestSelectorResult() {
        VCSReader gitReader = getVCSReader();
        // try-with-resources: release the H2 MVStore file lock before surefire forks the test
        // JVM. With DB_CLOSE_DELAY=-1 the Maven JVM would otherwise hold the lock for the rest
        // of the build, and the test JVM's JdbcDataStore would fail with "Database may be
        // already in use".
        try (DataStore dataStore = buildDataStore(gitReader.getBranchName())) {
            long startQueryTime = System.currentTimeMillis();

            List<String> sourceFilesDirs = getTiaSourceFilesDirs() != null ? Arrays.asList(getTiaSourceFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(sourceFilesDirs);
            List<String> testFilesDirs = getTiaTestFilesDirs() != null ? Arrays.asList(getTiaTestFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(testFilesDirs);

            TestSelector testSelector = new TestSelector(dataStore);
            LibraryImpactAnalysisConfig libraryConfig = buildLibraryImpactAnalysisConfig();
            StaticTestSelectionConfig staticMappingConfig = buildStaticTestSelectionConfig();
            TestSelectorResult testSelectorResult = testSelector.selectTestsToIgnore(gitReader, sourceFilesDirs,
                    testFilesDirs, isCheckLocalChanges(), libraryConfig, staticMappingConfig, isTiaUpdateDBMapping());
            getLog().debug("Time to analyze test selection data (sec): " + (System.currentTimeMillis() - startQueryTime) / 1000);
            return testSelectorResult;
        }
    }

    /**
     * Claim this runner's group of an already-planned distributed run and resolve the suites it
     * must run and skip, without repeating any of the planning work.
     *
     * <p>Validates the distributed configuration first, then opens the shared datastore and claims
     * through {@link DistributedRunnerAssignment}, which both build tools share so a Maven and a
     * Gradle runner cannot disagree about which suites a group owns.
     *
     * <p>Two of the three claim outcomes are failures, and both fail the build here rather than
     * degrade to a warning: a run id with no plan (this build was superseded, or was never planned)
     * and a workspace on a different commit than the plan was built by diffing. A runner that
     * cannot tell whether its share of the suite ran has no way to report that, so exiting
     * successfully would report a green build for untested code. The third outcome - every group
     * already claimed - is the legitimate surplus runner, and returns an assignment that runs
     * nothing.
     *
     * @return this runner's assignment, either its claimed group's suites or the run-nothing
     *         assignment of a surplus runner
     * @throws MojoExecutionException if the distributed configuration is invalid, or if the run
     *                                cannot be claimed because it is absent or was planned against
     *                                a different commit
     */
    private DistributedRunnerAssignment claimDistributedRunGroup() throws MojoExecutionException {
        DistributedRunConfig config = validatedDistributedRunConfig();
        VCSReader vcsReader = getVCSReader();

        // try-with-resources for the same reason as getTestSelectorResult: release the datastore
        // before surefire forks the test JVM.
        try (DataStore dataStore = buildDataStore(vcsReader.getBranchName())) {
            DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                    config, vcsReader.getHeadCommit(), System.currentTimeMillis());

            if (assignment.isClaimed()){
                getLog().info("Tia distributed run '" + config.getRunId() + "': runner '"
                        + assignment.getRunnerKey() + "' claimed group " + assignment.getGroupNumber()
                        + " and will run " + assignment.getTestsToRun().size() + " test suite(s).");
            } else {
                getLog().info("Tia distributed run '" + config.getRunId() + "': runner '"
                        + assignment.getRunnerKey() + "' claimed no group - every group was already "
                        + "claimed, so this runner will run no tests. This is expected when the "
                        + "pipeline fans out to more jobs than the plan has groups.");
            }
            return assignment;
        } catch (IllegalStateException e) {
            throw new MojoExecutionException("This runner could not claim its share of the "
                    + "distributed test run: " + e.getMessage(), e);
        }
    }

    /**
     * Validate the distributed run properties this runner was given, enforcing the same
     * preconditions and the same configuration rules the planner enforced - a runner pointed at an
     * embedded database cannot see the plan at all, and one that disagreed with the planner about
     * the run's shape would be claiming from a run nobody planned. The reactor-size rule applies
     * here too: see the inline comment on the {@code check} call for why a claim, not only a plan,
     * must be rejected on a multi-module reactor.
     *
     * @return the validated distributed run configuration
     * @throws MojoExecutionException if a precondition fails or the configuration is invalid; the
     *                                message names the property to fix
     */
    private DistributedRunConfig validatedDistributedRunConfig() throws MojoExecutionException {
        try {
            // The reactor-size rule belongs here too, not only on tia-dist-plan: prepare-agent is
            // bound to the INITIALIZE phase, so Maven runs it once per reactor module rather than
            // once for the whole build. On a multi-module reactor each module's execution would
            // claim its own group from the plan, so suites end up assigned to a group whose runner
            // lives in a different module - nobody runs them, and the build still reports success.
            // Passing the reactor's real size here, the same way AbstractTiaDistPlanMojo does, lets
            // DistributedRunPreconditions.check reject that shape before any group is claimed.
            DistributedRunPreconditions.check(isTiaEnabled(), getReactorProjects().size(), getTiaDBUrl(),
                    getTiaDBDialect(), isTiaCheckLocalChanges());
            // forRunner, not validated: how the build was split is the planner's decision and is
            // already recorded in the plan being claimed from. Requiring the grouping properties
            // here would make every runner job repeat configuration only the planning job uses, and
            // would accept a value disagreeing with the plan's while silently ignoring it.
            return DistributedRunConfig.forRunner(getTiaRunId(), getTiaDistributedRunnerKey());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new MojoExecutionException("Distributed run configuration is invalid: "
                    + e.getMessage(), e);
        }
    }

    private void writeIgnoredTestsToFile(Set<String> testsToIgnore){
        String ignoredTestsFilename = getIgnoreTestsFilename();
        writeTestsToFile(ignoredTestsFilename, testsToIgnore);
    }

    private void writeSelectedTestsToFile(Set<String> selectedTests){
        String selectedTestsFilename = getSelectedTestsFilename();
        writeTestsToFile(selectedTestsFilename, selectedTests);
    }

    private void writeTestsToFile(String filename, Set<String> tests){
        FileWriter fileWriter = null;
        try {

            File file = new File(filename);
            file.getParentFile().mkdirs();
            fileWriter = new FileWriter(file);

            if (tests.isEmpty()){
                fileWriter.write("");
            }else{
                for (String str : tests) {
                    fileWriter.write(str + System.lineSeparator());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                fileWriter.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String getIgnoreTestsFilename(){
        return getTiaBuildDir() + "/" + IGNORED_TESTS_FILENAME;
    }

    private String getSelectedTestsFilename(){
        return getTiaBuildDir() + "/" + SELECTED_TESTS_FILENAME;
    }

    private String getLibraryJarsFilename(){
        return getTiaBuildDir() + "/" + LIBRARY_JARS_FILENAME;
    }

    /**
     * Resolve the configured {@code tiaSourceLibs} coordinates to absolute JAR paths using the
     * source project's pom and write them (one per line) to {@code ${tiaBuildDir}/library-jars.txt}.
     * The TIA javaagent reads this file at {@code premain} time in the forked test JVM and
     * publishes the contents as the {@code tiaLibraryJars} system property for {@code JacocoClient}.
     *
     * @return absolute path of the file written, or {@code null} when {@code tiaSourceLibs} is
     *         unset or no JARs resolved.
     */
    private String writeLibraryJarsFile(){
        String libraries = getTiaSourceLibs();
        if (libraries == null || libraries.trim().isEmpty()){
            return null;
        }

        LibraryJarResolver resolver = new LibraryJarResolver(
                projectBuilder, session.getProjectBuildingRequest(), getLog());
        String jarsCsv = resolver.resolveLibraryJarsCsv(libraries, getTiaSourceProjectDir());

        if (jarsCsv == null || jarsCsv.isEmpty()){
            return null;
        }

        getLog().debug("tiaLibraryJars resolved to: " + jarsCsv);
        Set<String> jars = new LinkedHashSet<>(Arrays.asList(jarsCsv.split(",")));
        String filename = getLibraryJarsFilename();
        writeTestsToFile(filename, jars);
        return filename;
    }

    private AgentOptions buildTiaAgentOptions(String libraryJarsFile, String drainResultFile, String forkPropertiesFile){
        AgentOptions agentOptions = new AgentOptions();
        agentOptions.setIgnoreTestsFile(getIgnoreTestsFilename());
        agentOptions.setSelectedTestsFile(getSelectedTestsFilename());
        if (libraryJarsFile != null){
            agentOptions.setLibraryJarsFile(libraryJarsFile);
        }
        if (drainResultFile != null){
            agentOptions.setDrainResultFile(drainResultFile);
        }
        if (forkPropertiesFile != null){
            agentOptions.setForkPropertiesFile(forkPropertiesFile);
        }
        return agentOptions;
    }

    /**
     * Write the system properties the forked test JVM needs (database connection, project dirs,
     * update flags) to a {@code java.util.Properties} file, so the Tia agent can republish them via
     * {@code premain}. This removes the need for the user to mirror these into the Surefire
     * {@code systemPropertyVariables} (Gradle forwards them automatically); using a file rather than
     * inline command-line properties keeps long values - {@code tiaClassFilesDirs} (a CSV) and
     * {@code testClassesDir} - off the command line and clear of the comma-delimited agent option
     * parser. Entries with a {@code null} value are skipped, so an unset {@code tiaDBUrl} simply
     * leaves the fork in embedded mode.
     *
     * <p>On a distributed run this file is also the handoff for the claim: the resolved runner key
     * and the claimed group number are written here because the forked JVM completes the group and
     * elects the run's sealer, and can reconstruct neither value. The runner key in particular must
     * be the one the claim was recorded under - the coordinator may have derived it, and a fork
     * that derived its own would produce a different key and orphan the claim. Nothing is written
     * for a non-distributed run, so an ordinary build's fork sees exactly the properties it always
     * did.
     *
     * @param assignment this runner's claimed share of a distributed run, or {@code null} for a
     *                   non-distributed build
     * @return absolute path of the file written
     */
    String writeForkPropertiesFile(final DistributedRunnerAssignment assignment){
        Map<String, String> props = new LinkedHashMap<>();
        props.put("tiaEnabled", String.valueOf(isTiaEnabled()));
        props.put("tiaUpdateDBMapping", String.valueOf(isTiaUpdateDBMapping()));
        props.put("tiaUpdateDBStats", String.valueOf(isTiaUpdateDBStats()));
        props.put("tiaUpdateDBTestRunHistory", String.valueOf(isTiaUpdateDBTestRunHistory()));
        props.put("tiaProjectDir", getTiaProjectDir());
        props.put("tiaClassFilesDirs", getTiaClassFilesDirs());
        props.put("testClassesDir", getProject().getBuild().getTestOutputDirectory());
        props.put("tiaDBFilePath", getTiaDBFilePath());
        props.put("tiaDBUrl", getTiaDBUrl());
        props.put("tiaDBDialect", getTiaDBDialect());
        props.put("tiaDBUser", getTiaDBUser());
        props.put("tiaDBPassword", getTiaDBPassword());

        if (assignment != null){
            // The property names and the rendering of the values are owned by
            // DistributedForkProperties, which is also what the forked JVM's listener reads them
            // back with - so the two halves of this handoff cannot drift apart on a name. A fork
            // that resolved no context because of a renamed property would silently persist as a
            // single host and seal a build the other runners are still contributing to.
            props.putAll(DistributedForkProperties.forkProperties(getTiaRunId(),
                    assignment.getRunnerKey(), assignment.getGroupNumber()));
        }

        String filename = getTiaBuildDir() + "/" + FORK_PROPERTIES_FILENAME;
        try {
            ForkSystemProperties.write(props, new File(filename));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return filename;
    }

    /**
     * Serialize the {@link LibraryImpactDrainResult} to a file so the test listener in the
     * forked JVM can deserialize it and pass it to {@code TestRunnerService} for post-test-run cleanup.
     *
     * @return absolute path of the file written, or {@code null} if no drain result.
     */
    private String writeDrainResultFile(LibraryImpactDrainResult drainResult) {
        if (drainResult == null || !drainResult.hasDrainedBatches()) {
            return null;
        }
        String filename = getDrainResultFilename();
        java.io.File file = new java.io.File(filename);
        LibraryImpactDrainResultSerializer.serialize(drainResult, file);
        return filename;
    }

    private String getDrainResultFilename(){
        return getTiaBuildDir() + "/" + DRAIN_RESULT_FILENAME;
    }

    /**
     * Check if Tia should analyze local changes.
     * If we're updating the DB, we shouldn't check for local changes as the DB needs to be in sync with
     * committed changes only.
     *
     * @return
     */
    private boolean isCheckLocalChanges(){
        if (isTiaUpdateDBMapping() && isTiaCheckLocalChanges()){
            getLog().info("Disabling the check for local changes as Tia is configured to update the mapping in the DB.");
            return false;
        } else{
            return isTiaCheckLocalChanges();
        }
    }

    /**
     * Add the test suite agent after the jacoco agent in the command line.
     *
     * Note: The order does matter (as a work-around for 'java.lang.NoSuchFieldException: $jacocoAccess' error).
     * The agent uses UUID which is being modified by Jacoco. Do the modification by Jacoco first before it
     * gets used by the
     *
     * https://github.com/jacoco/jacoco/issues/551
     *
     * @param arguments the VM arguments
     * @param agentJarFile the agent JAR file
     * @param agentOptions the agent options
     * @return the agent command line arguments
     */
    public String addVMArguments(final String arguments, final File agentJarFile, final AgentOptions agentOptions) {
        final List<String> args = CommandLineSupport.split(arguments);
        final String plainAgent = format("-javaagent:%s", agentJarFile);

        for (final Iterator<String> i = args.iterator(); i.hasNext();) {
            String arg = i.next();

            // if we already have Tia agent on the surefire argument, remove it. We'll configure it in this plugin.
            if (arg.startsWith(plainAgent)) {
                i.remove();
            }

            // If we're running Tia but not updating the DB test mapping, we don't need Jacoco - remove it. When we're running Tia we
            // have control over Jacoco solely for use by Tia. So it should be safe to remove Jacoco.
            if (isTiaEnabled() && !isTiaUpdateDBMapping()){
                if(arg.matches("^-javaagent.*org.jacoco.agent.*")){
                    getLog().info("Tia is enabled but not updating the DB. Jacoco is not needed. Removing it from the argLine.");
                    i.remove();
                }
            }
        }

        args.add(getVMArgument(agentJarFile, agentOptions));
        return CommandLineSupport.quote(args);
    }

    /**
     * Generate required JVM argument based on current configuration and
     * supplied agent jar location.
     *
     * @param agentJarFile
     *            location of the JaCoCo Agent Jar
     * @param agentOptions
     *             options to pass through to the agent
     * @return Argument to pass to create new VM with coverage enabled
     */
    private String getVMArgument(final File agentJarFile, final AgentOptions agentOptions) {
        return format("-javaagent:%s=%s", agentJarFile, agentOptions.toCommandLineOptionsString());
    }

    File getAgentJarFile() {
        final Artifact agentArtifact = getPluginArtifactMap().get(getAgentArtifactName());
        return agentArtifact.getFile();
    }

    String getEffectivePropertyName() {
        if (isPropertyNameSpecified()) {
            return getPropertyName();
        }
        return SUREFIRE_ARG_LINE;
    }

    boolean isPropertyNameSpecified() {
        return getPropertyName() != null && !"".equals(getPropertyName());
    }

    public abstract String getAgentArtifactName();

    public abstract Map<String, Artifact> getPluginArtifactMap();

    /**
     * Check if Tia is enabled. Used to determine if we should load the Tia agent and analyse the
     * changes and Ignore tests not impacted by the changes.
     *
     * @return
     */
    private boolean isEnabled(){
        boolean enabled = isTiaEnabled();
        getLog().info("Tia AgentMojo: enabled: " + enabled + ", update mapping: " + isTiaUpdateDBMapping() +
                ", update stats: " + isTiaUpdateDBStats() +
                ", update test run history: " + isTiaUpdateDBTestRunHistory());

        /**
         * If the user specified specific individual tests to run, disable Tia so those tests are run
         * and guaranteed to be the only tests to run.
         */
        if (enabled){
            String userSpecifiedTests = System.getProperty("test");
            boolean hasUserSpecifiedTests = userSpecifiedTests != null && !userSpecifiedTests.isEmpty();
            if (hasUserSpecifiedTests){
                getLog().info("User has specified tests, disabling Tia");
                enabled = false;
            }
        }

        return enabled;
    }

    public String getPropertyName(){
        return propertyName;
    }
}
