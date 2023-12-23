package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.tiatesting.core.agent.AgentOptions;

import java.io.File;
import java.util.*;

import static java.lang.String.format;

public abstract class TiaAgentMojo extends AbstractMojo {

    /**
     * Name of the property used in maven-surefire-plugin.
     */
    static final String SUREFIRE_ARG_LINE = "argLine";

    /**
     * Maven project.
     */
    @Parameter(property = "project", readonly = true)
    private MavenProject project;

    /**
     * Allows to specify a property which will contains settings for JaCoCo Agent.
     * If not specified, then "argLine" would be used for "jar" packaging and
     * "tycho.testArgLine" for "eclipse-test-plugin".
     */
    @Parameter(property = "jacoco.propertyName")
    String propertyName;

    /**
     * The file path to the root folder of the project being analyzed.
     *
     */
    @Parameter(property = "tiaProjectDir")
    String tiaProjectDir;

    /**
     * The file path for the saved DB containing the previous analysis of the project.
     */
    @Parameter(property = "tiaDBFilePath")
    String tiaDBFilePath;

    /**
     * The source files directories for the project being analyzed.
     */
    @Parameter(property = "tiaSourceFilesDirs")
    String tiaSourceFilesDirs;

    /**
     * Is TIA enabled?
     */
    @Parameter(property = "tiaEnabled")
    boolean tiaEnabled;

    /**
     * Should the TIA DB be updated with this test run?
     */
    @Parameter(property = "tiaUpdateDB")

    String tiaUpdateDB;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!isEnabled()){
            getLog().info("TIA is disabled");
            return;
        }

        final String name = getEffectivePropertyName();
        final Properties projectProperties = getProject().getProperties();
        final String oldValue = projectProperties.getProperty(name);
        final AgentOptions agentOptions = buildAgentOptions();
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

    private AgentOptions buildAgentOptions(){
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

        return agentOptions;
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
            if (i.next().startsWith(plainAgent)) {
                i.remove();
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
        final Artifact jacocoAgentArtifact = getPluginArtifactMap().get(getAgentArtifactName());
        return jacocoAgentArtifact.getFile();
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
     * Check if TIA is enabled. Used to determine if we should load the TIA agent and analaze the
     * changes and Ignore tests not impacted by the changes.
     *
     * @return
     */
    private boolean isEnabled(){
        // TODO test this!
        boolean enabled = isTiaEnabled();
        boolean updateDB = isTiaUpdateDB();
        String userSpecifiedTests = System.getProperty("test");
        getLog().info("tiaEnabled: " + enabled);
        getLog().info("tiaUpdateDB: " + updateDB);

        /**
         * TIA is enabled but we're not updating the DB. The DB is usually updated via the CI so
         * this indicates the tests are being run locally.
         * If the user specified specific individual tests to run, disable TIA so those tests are run
         * and guaranteed to be the only tests to run.
         */
        if (enabled && !updateDB){
            boolean hasUserSpecifiedTests = userSpecifiedTests != null && !userSpecifiedTests.isEmpty();
            enabled = !hasUserSpecifiedTests; // disable TIA if the user has specified tests to run
        }

        return enabled;
    }

    public MavenProject getProject(){
        return project;
    }

    public String getPropertyName(){
        return propertyName;
    }

    public String getTiaProjectDir(){
        return tiaProjectDir;
    }

    public String getTiaDBFilePath(){
        return tiaDBFilePath;
    }

    public String getTiaSourceFilesDirs() {
        return tiaSourceFilesDirs;
    }

    public boolean isTiaEnabled() {
        return tiaEnabled;
    }

    public boolean isTiaUpdateDB() {
        return Boolean.parseBoolean(tiaUpdateDB);
    }
}
