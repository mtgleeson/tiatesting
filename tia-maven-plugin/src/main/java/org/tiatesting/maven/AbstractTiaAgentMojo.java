package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.agent.CommandLineSupport;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
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

    /**
     * Allows to specify a property which will contains settings for JaCoCo Agent.
     * If not specified, then "argLine" would be used for "jar" packaging and
     * "tycho.testArgLine" for "eclipse-test-plugin".
     */
    @Parameter(property = "jacoco.propertyName")
    String propertyName;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!isEnabled()){
            return;
        }

        final String name = getEffectivePropertyName();
        final Properties projectProperties = getProject().getProperties();
        final String oldValue = projectProperties.getProperty(name);
        final AgentOptions agentOptions = buildTiaAgentOptions();
        final String newValue = addVMArguments(oldValue, getAgentJarFile(), agentOptions);
        getLog().info(name + " set to " + newValue);
        projectProperties.setProperty(name, newValue);

        TestSelectorResult testSelectorResult = getTestSelectorResult();
        writeIgnoredTestsToFile(testSelectorResult.getTestsToIgnore());
        writeSelectedTestsToFile(testSelectorResult.getTestsToRun());

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
        DataStore dataStore = new H2DataStore(getTiaDBFilePath(), gitReader.getBranchName());
        long startQueryTime = System.currentTimeMillis();

        List<String> sourceFilesDirs = getTiaSourceFilesDirs() != null ? Arrays.asList(getTiaSourceFilesDirs().split(",")) : null;
        List<String> testFilesDirs = getTiaTestFilesDirs() != null ? Arrays.asList(getTiaTestFilesDirs().split(",")) : null;

        TestSelector testSelector = new TestSelector(dataStore);
        TestSelectorResult testSelectorResult = testSelector.selectTestsToIgnore(gitReader, sourceFilesDirs, testFilesDirs, isCheckLocalChanges());
        getLog().debug("Time to analyze test selection data (sec): " + (System.currentTimeMillis() - startQueryTime) / 1000);
        return testSelectorResult;
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

    private AgentOptions buildTiaAgentOptions(){
        AgentOptions agentOptions = new AgentOptions();
        agentOptions.setIgnoreTestsFile(getIgnoreTestsFilename());
        agentOptions.setSelectedTestsFile(getSelectedTestsFilename());
        return agentOptions;
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
     * @param arguments
     * @param agentJarFile
     * @param agentOptions
     * @return
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
                ", update stats: " + isTiaUpdateDBStats());

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
