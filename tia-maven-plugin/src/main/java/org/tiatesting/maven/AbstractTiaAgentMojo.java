package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
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
import org.tiatesting.core.testrunner.RunEnvironment;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.WorkspaceIdentity;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.SecretFile;
import org.tiatesting.core.persistence.CredentialResolver;
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

    /**
     * Allows to specify a property which will contains settings for JaCoCo Agent.
     * If not specified, then "argLine" would be used for "jar" packaging and
     * "tycho.testArgLine" for "eclipse-test-plugin".
     */
    @Parameter(property = "jacoco.propertyName")
    String propertyName;

    /** Project-context key prefix under which each execution records the schema it claimed. */
    private static final String SCHEMA_CLAIM_CONTEXT_PREFIX = "tia.schema.claimed.";

    /**
     * This mojo's own execution, injected so the collision refusal can name which two executions
     * clashed rather than only that two did.
     */
    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    MojoExecution mojoExecution;

    /**
     * Prepare the forked test JVM: work out which test suites it must skip and which it must run,
     * write those lists and the properties the Tia agent republishes in the fork, and add the agent
     * to the surefire {@code argLine}.
     *
     * <p>How the two suite lists are arrived at is the one thing that differs between an ordinary
     * and a distributed build. An ordinary build runs the test selection here. A distributed build
     * must not: the plan produced by {@code dist-plan} already ran the VCS diff, the static
     * rules and the library-impact drain once, for every runner, and its output is in the shared
     * database. So a distributed build claims a group from that plan instead - see
     * {@link #claimDistributedRunGroup(WorkspaceIdentity)}.
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

        // One identity for the whole goal. When tiaBranch and tiaCommitValue are both configured
        // nothing below constructs a VCS reader at all, which is what lets a distributed runner
        // hold nothing but a checked-out tree; when they are not, the fallback opens one repository
        // handle - or one Perforce server connection - rather than one per step.
        try (WorkspaceIdentity workspaceIdentity = workspaceIdentity()) {
            prepareForkedTestRun(workspaceIdentity);
        }
    }

    /**
     * Do the goal's work against an already-resolved workspace identity: refuse a colliding schema,
     * work out the two suite lists, and write everything the forked test JVM needs.
     *
     * <p>Split out of {@link #execute()} only so the identity can be opened and closed around it in
     * one try-with-resources block rather than resolved separately by each step below, which on
     * Perforce would mean a separate server connection per step.
     *
     * @param workspaceIdentity this build's branch and commit, and the shared VCS reader for the
     *                          steps that need more than those two values
     * @throws MojoExecutionException if this module has a colliding Tia execution, or if a
     *                                distributed runner cannot claim its share of the planned run
     */
    private void prepareForkedTestRun(final WorkspaceIdentity workspaceIdentity)
            throws MojoExecutionException {
        refuseCollidingSchema(workspaceIdentity);

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
            assignment = claimDistributedRunGroup(workspaceIdentity);
            testsToIgnore = assignment.getTestsToIgnore();
            testsToRun = assignment.getTestsToRun();
            drainResult = null;
        } else {
            TestSelectorResult testSelectorResult = getTestSelectorResult(workspaceIdentity);
            testsToIgnore = testSelectorResult.getTestsToIgnore();
            testsToRun = testSelectorResult.getTestsToRun();
            drainResult = testSelectorResult.getLibraryImpactDrainResult();
        }

        String forkPropertiesFile = writeForkPropertiesFile(assignment, workspaceIdentity);
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

    /**
     * Run the test selection for an ordinary, non-distributed build.
     *
     * <p>This is the one caller that needs more than the branch and the commit: the selection diffs
     * the workspace, so it takes the identity's own reader rather than constructing a second one.
     * The branch still comes from the identity, so a configured {@code tiaBranch} selects the schema
     * here exactly as it does everywhere else.
     *
     * @param workspaceIdentity this build's workspace identity, supplying both the branch and the
     *                          VCS reader the diff is read through
     * @return the suites this build must run and skip, and any library-impact drain result
     * @throws MojoExecutionException if the datastore cannot be opened
     */
    private TestSelectorResult getTestSelectorResult(final WorkspaceIdentity workspaceIdentity)
            throws MojoExecutionException {
        VCSReader gitReader = workspaceIdentity.openVCSReader();
        // try-with-resources: release the H2 MVStore file lock before surefire forks the test
        // JVM. With DB_CLOSE_DELAY=-1 the Maven JVM would otherwise hold the lock for the rest
        // of the build, and the test JVM's JdbcDataStore would fail with "Database may be
        // already in use".
        try (DataStore dataStore = buildDataStore(workspaceIdentity.getBranch())) {
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
     * <p>Both values it needs - the branch whose schema holds the plan, and the commit the claim is
     * verified against - come from the workspace identity, so a runner given {@code tiaBranch} and
     * {@code tiaCommitValue} claims without a version control system being present at all.
     *
     * @param workspaceIdentity this build's branch and commit
     * @return this runner's assignment, either its claimed group's suites or the run-nothing
     *         assignment of a surplus runner
     * @throws MojoExecutionException if the distributed configuration is invalid, or if the run
     *                                cannot be claimed because it is absent or was planned against
     *                                a different commit
     */
    private DistributedRunnerAssignment claimDistributedRunGroup(final WorkspaceIdentity workspaceIdentity)
            throws MojoExecutionException {
        DistributedRunConfig config = validatedDistributedRunConfig();
        logVcsFallbackForARunner();

        // try-with-resources for the same reason as getTestSelectorResult: release the datastore
        // before surefire forks the test JVM.
        try (DataStore dataStore = buildDataStore(workspaceIdentity.getBranch())) {
            DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                    config, workspaceIdentity.getCommitValue(), System.currentTimeMillis());

            if (assignment.isClaimed()){
                // A seed run's group deliberately carries no suite names - there is no mapping yet
                // to split - and its runner ignores nothing and executes everything it discovers.
                // Reporting the assigned count there would say "will run 0 test suite(s)" about the
                // one run that executes the entire suite.
                if (assignment.isSeedRun()){
                    getLog().info("Tia distributed run '" + config.getRunId() + "': runner '"
                            + assignment.getRunnerKey() + "' claimed group "
                            + assignment.getGroupNumber() + ". This is a seed run - there is no "
                            + "stored mapping for this branch yet, so the plan carries no suite "
                            + "names and this runner will execute every test it discovers and "
                            + "record the mapping the next build plans from.");
                } else {
                    getLog().info("Tia distributed run '" + config.getRunId() + "': runner '"
                            + assignment.getRunnerKey() + "' claimed group "
                            + assignment.getGroupNumber() + " and will run "
                            + assignment.getTestsToRun().size() + " test suite(s).");
                }
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
     * Tell a distributed runner that it is about to read the version control system for a value it
     * could have been handed, naming the property that would avoid it.
     *
     * <p>Logged at INFO rather than warned about: a developer running a distributed build from a
     * workspace that has a repository is the ordinary case for this path, and there is nothing wrong
     * with it. It is worth saying once all the same, because the same build on a CI runner holding
     * only a checked-out tree is the one that fails, and the message names the fix before it
     * becomes a failure.
     */
    private void logVcsFallbackForARunner() {
        List<String> unset = new ArrayList<>(2);
        if (getTiaBranch() == null || getTiaBranch().trim().isEmpty()) {
            unset.add(WorkspaceIdentity.PROP_BRANCH);
        }
        if (getTiaCommitValue() == null || getTiaCommitValue().trim().isEmpty()) {
            unset.add(WorkspaceIdentity.PROP_COMMIT_VALUE);
        }
        if (!unset.isEmpty()) {
            getLog().info("Tia distributed run: " + String.join(" and ", unset) + " "
                    + (unset.size() == 1 ? "is" : "are") + " not set, so this runner reads "
                    + (unset.size() == 1 ? "that value" : "those values")
                    + " from the version control system. Set "
                    + (unset.size() == 1 ? "it" : "them") + " to run on a machine with no version "
                    + "control access.");
        }
    }

    /**
     * Refuse a second Tia execution in this module that resolves to the schema an earlier one
     * already claimed.
     *
     * <p>Two Tia-enabled executions sharing a schema - a surefire and a failsafe execution, say -
     * delete each other's tracked suites, because each sees only the suites it ran and treats every
     * other tracked suite as deleted. They also share the one {@code tia_core} row and therefore the
     * one stored commit value, so whichever ran less recently diffs from a commit it never covered
     * and under-selects. Neither failure fails a build, so a refusal here is the only thing that
     * surfaces the misconfiguration.
     *
     * <p><b>Uses the resolved value, not the configured text.</b> Each execution records the schema
     * it actually resolved to in the project's context, and the collision is detected between two
     * recordings. Reading the configured {@code <tiaDBSchemaSuffix>} out of the POM instead would
     * miss the very case this exists for: an undefined property reference evaluates to null, so two
     * executions that both meant to declare a suffix would silently share the unsuffixed schema
     * while their configuration text looked different.
     *
     * <p><b>Scope.</b> This sees the executions of one Maven invocation, which is where surefire and
     * failsafe both run ({@code mvn verify}). Two executions split across separate invocations are
     * not caught - nor is a multi-module reactor, where each module's agent resolves its own schema
     * and supporting that properly is separate work.
     *
     * @param workspaceIdentity this build's workspace identity, supplying the branch the schema
     *                          name is built from
     * @throws MojoExecutionException if another execution in this module already claimed this schema
     */
    private void refuseCollidingSchema(final WorkspaceIdentity workspaceIdentity)
            throws MojoExecutionException {
        String schema = BranchSchema.schemaName(workspaceIdentity.getBranch(), getTiaDBSchemaSuffix());
        String contextKey = SCHEMA_CLAIM_CONTEXT_PREFIX + schema;
        String executionId = mojoExecution == null ? "(unknown)" : mojoExecution.getExecutionId();

        Object existing = getProject().getContextValue(contextKey);
        if (existing != null && !existing.equals(executionId)) {
            throw new MojoExecutionException("Tia: this module has more than one Tia execution "
                    + "writing to the schema '" + schema + "' (executions '" + existing + "' and '"
                    + executionId + "'). They would delete each other's tracked test suites and "
                    + "share one stored commit value, which silently costs selectivity and can "
                    + "silently under-select. Give each execution its own schema with "
                    + "<tiaDBSchemaSuffix>, e.g. 'unit' on the surefire execution and 'integration' "
                    + "on the failsafe one.");
        }
        getProject().setContextValue(contextKey, executionId);
    }

    /**
     * Validate the distributed run properties this runner was given, enforcing the same
     * preconditions and the same configuration rules the planner enforced - a runner pointed at an
     * embedded database cannot see the plan at all, and one that disagreed with the planner about
     * the run's shape would be claiming from a run nobody planned. The reactor-size rule applies
     * here too: see the inline comment on the {@code check} call for why a claim, not only a plan,
     * must be rejected on a multi-module reactor. When that rule is what fails, the rejection names
     * every project found in the reactor via {@link #withReactorProjectNamesIfRelevant}, the same
     * way {@link AbstractTiaDistPlanMojo#execute()} does for the planning side of the same rule.
     *
     * @return the validated distributed run configuration
     * @throws MojoExecutionException if a precondition fails or the configuration is invalid; the
     *                                message names the property to fix, and additionally names
     *                                every reactor project when the multi-project rule is what
     *                                failed
     */
    private DistributedRunConfig validatedDistributedRunConfig() throws MojoExecutionException {
        List<MavenProject> reactorProjects = getReactorProjects();
        try {
            // The reactor-size rule belongs here too, not only on dist-plan: prepare-agent is
            // bound to the INITIALIZE phase, so Maven runs it once per reactor module rather than
            // once for the whole build. On a multi-module reactor each module's execution would
            // claim its own group from the plan, so suites end up assigned to a group whose runner
            // lives in a different module - nobody runs them, and the build still reports success.
            // Passing the reactor's real size here, the same way AbstractTiaDistPlanMojo does, lets
            // DistributedRunPreconditions.check reject that shape before any group is claimed.
            DistributedRunPreconditions.check(isTiaEnabled(), reactorProjects.size(), getTiaDBUrl(),
                    getTiaDBDialect(), isTiaCheckLocalChanges());
            // forRunner, not validated: how the build was split is the planner's decision and is
            // already recorded in the plan being claimed from. Requiring the grouping properties
            // here would make every runner job repeat configuration only the planning job uses, and
            // would accept a value disagreeing with the plan's while silently ignoring it.
            return DistributedRunConfig.forRunner(getTiaRunId(), getTiaDistributedRunnerKey());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new MojoExecutionException("Distributed run configuration is invalid: "
                    + withReactorProjectNamesIfRelevant(e.getMessage(), reactorProjects), e);
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
     * {@code tiaTestClassesDirs} - off the command line and clear of the comma-delimited agent option
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
     * @param workspaceIdentity this build's branch and commit, forwarded so the fork does not have
     *                          to resolve either for itself
     * @return absolute path of the file written
     * @throws MojoExecutionException if the database password cannot be resolved for the fork
     */
    String writeForkPropertiesFile(final DistributedRunnerAssignment assignment,
                                   final WorkspaceIdentity workspaceIdentity)
            throws MojoExecutionException {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("tiaEnabled", String.valueOf(isTiaEnabled()));
        // The branch and commit this build JVM already resolved. Forwarded for every build, not
        // only a distributed one, so the forked test JVM never opens a repository of its own to
        // learn what this goal has already established - and so the two can never disagree about
        // which branch's schema the run belongs to.
        props.put(WorkspaceIdentity.PROP_BRANCH, workspaceIdentity.getBranch());
        props.put(WorkspaceIdentity.PROP_COMMIT_VALUE, workspaceIdentity.getCommitValue());
        props.put(ForkSystemProperties.PROP_UPDATE_DB_MAPPING, String.valueOf(isTiaUpdateDBMapping()));
        props.put(ForkSystemProperties.PROP_UPDATE_DB_TEST_RUN_HISTORY, String.valueOf(isTiaUpdateDBTestRunHistory()));
        // Null when not declared, which ForkSystemProperties.write skips - so the fork sees no
        // property at all and RunEnvironment falls back to detecting the source itself.
        props.put(RunEnvironment.PROP_RUN_SOURCE, getTiaRunSource());
        // Likewise null-skipped: an undeclared suffix must leave the fork resolving the plain
        // tia_<branch> schema rather than one named "null".
        props.put(DataStoreFactory.PROP_DB_SCHEMA_SUFFIX, getTiaDBSchemaSuffix());
        props.put("tiaProjectDir", getTiaProjectDir());
        props.put("tiaClassFilesDirs", getTiaClassFilesDirs());
        props.put(ForkSystemProperties.PROP_TEST_CLASSES_DIRS,
                getProject().getBuild().getTestOutputDirectory());
        props.put("tiaDBFilePath", getTiaDBFilePath());
        props.put("tiaDBUrl", getTiaDBUrl());
        props.put("tiaDBDialect", getTiaDBDialect());
        // The resolved username, not the raw parameter: one supplied by a settings.xml <server>
        // entry has to reach the fork too. Forwarding the parameter left the fork falling back to
        // TIA_DB_USER and then to H2's "tia" default, so the build JVM and the fork connected as
        // different users - invisible on H2 with the username "tia", an authentication failure on
        // any other vendor. Null when nothing is configured, which ForkSystemProperties.write
        // skips, leaving the fork to resolve the environment it already inherits.
        props.put("tiaDBUser", resolveDbUser());
        // A path, never the password. Every key written here is republished as a system property in
        // the forked test JVM by ForkSystemProperties.applyToSystemProperties, and surefire dumps
        // the fork's system properties into target/surefire-reports/TEST-*.xml - the artifact CI
        // publishes. A path is not a secret; the password is. Null when the fork needs no path
        // because the password is unset or comes from the environment it already inherits, and
        // ForkSystemProperties.write skips nulls, so such a build writes no key at all.
        props.put(CredentialResolver.PROP_DB_PASSWORD_FILE, resolvePasswordFileForFork());

        if (assignment != null){
            // The property names and the rendering of the values are owned by
            // DistributedForkProperties, which is also what the forked JVM's listener reads them
            // back with - so the two halves of this handoff cannot drift apart on a name. A fork
            // that resolved no context because of a renamed property would silently persist as a
            // single host and seal a build the other runners are still contributing to.
            props.putAll(DistributedForkProperties.forkProperties(getTiaRunId(),
                    assignment.getRunnerKey(), assignment.getGroupNumber()));
        }

        String filename = getForkPropertiesFilename();
        try {
            ForkSystemProperties.write(props, new File(filename));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return filename;
    }

    /**
     * Give the forked test JVM a way to reach the database password without the password itself
     * crossing the fork boundary.
     *
     * <p>Three cases, and only the middle one puts a secret on disk. A password the user already
     * keeps in a file is referenced where it lies, so nothing is staged. A password configured in
     * the build - including one explicitly configured as empty - is staged into an owner-only file
     * outside the build directory, deleted when this JVM exits (see {@link SecretFile}). A build
     * that configures no password at all forwards nothing, because the surefire fork is a child of
     * this JVM and inherits its environment, so it resolves
     * {@value CredentialResolver#ENV_DB_PASSWORD} for itself.
     *
     * @return the path for the fork to read the password from, or {@code null} when the fork needs
     *         no path
     * @throws MojoExecutionException if the configured password cannot be resolved, or the file
     *                                cannot be staged
     */
    private String resolvePasswordFileForFork() throws MojoExecutionException {
        if (tiaDBPasswordFile != null && !tiaDBPasswordFile.trim().isEmpty()) {
            return tiaDBPasswordFile;
        }
        // Deliberately keyed off "configured at all", not off the resolved value being non-empty.
        // An explicit <tiaDBPassword></tiaDBPassword> means an empty password and must bypass the
        // environment fallback in the fork exactly as it does here - forwarding nothing would let a
        // TIA_DB_PASSWORD that happens to be set in the environment win in the fork while the build
        // JVM used the empty value, and the two would connect as different users.
        String configured = configuredPassword();
        if (configured == null) {
            return null;
        }
        try {
            return SecretFile.write(configured).toString();
        } catch (IOException e) {
            throw new MojoExecutionException("Tia could not stage the database password for the "
                    + "forked test JVM.", e);
        }
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
        getLog().info("Tia AgentMojo: enabled: " + enabled + ", update mapping (and stats): "
                + isTiaUpdateDBMapping()
                + ", update test run history: " + isTiaUpdateDBTestRunHistory());

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
