package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.agent.CommandLineSupport;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;

public abstract class AbstractTiaAgentMojo extends AbstractTiaMojo {

    /**
     * Name of the property used in maven-surefire-plugin.
     */
    static final String SUREFIRE_ARG_LINE = "argLine";

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
        getLog().info(name + " set to " + newValue); // TODO Mask the VCS password
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

    private AgentOptions buildTiaAgentOptions(){
        AgentOptions agentOptions = new AgentOptions();

        if (getTiaProjectDir() != null && !getTiaProjectDir().isEmpty()){
            agentOptions.setProjectDir(getTiaProjectDir());
        }

        if (getTiaDBFilePath() != null && !getTiaDBFilePath().isEmpty()){
            agentOptions.setDBFilePath(getTiaDBFilePath());
        }

        if (getTiaSourceFilesDirs() != null && !getTiaSourceFilesDirs().isEmpty()){
            agentOptions.setSourceFilesDirs(getTiaSourceFilesDirs());
        }

        if (getTiaTestFilesDirs() != null && !getTiaTestFilesDirs().isEmpty()){
            agentOptions.setTestFilesDirs(getTiaTestFilesDirs());
        }

        if (getTiaVcsServerUri() != null && !getTiaVcsServerUri().isEmpty()){
            agentOptions.setVcsServerUri(getTiaVcsServerUri());
        }

        if (getTiaVcsUserName() != null && !getTiaVcsUserName().isEmpty()){
            agentOptions.setVcsUserName(getTiaVcsUserName());
        }

        if (getTiaVcsPassword() != null && !getTiaVcsPassword().isEmpty()){
            agentOptions.setVcsPassword(getTiaVcsPassword());
        }

        if (getTiaVcsClientName() != null && !getTiaVcsClientName().isEmpty()){
            agentOptions.setVcsClientName(getTiaVcsClientName());
        }

        agentOptions.setCheckLocalChanges(getCheckLocalChanges());
        agentOptions.setUpdateDBMapping(String.valueOf(isTiaUpdateDBMapping()));
        agentOptions.setUpdateDBStats(String.valueOf(isTiaUpdateDBStats()));

        return agentOptions;
    }

    /**
     * Check if Tia should analyze local changes.
     * If we're updating the DB, we shouldn't check for local changes as the DB needs to be in sync with
     * committed changes only.
     *
     * @return
     */
    private String getCheckLocalChanges(){
        if (isTiaUpdateDBMapping() && isTiaCheckLocalChanges()){
            getLog().info("Disabling the check for local changes as Tia is configured to update the mapping in the DB.");
            return Boolean.toString(false);
        } else{
            return String.valueOf(isTiaCheckLocalChanges());
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
                ", update stats: " + isTiaUpdateDBMapping());

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