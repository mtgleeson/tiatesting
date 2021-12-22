package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;
import org.tiatesting.core.agent.AgentOptions;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;

public abstract class TiaAgentMojo extends AbstractMojo {

    /**
     * Name of the property used in maven-surefire-plugin.
     */
    static final String SUREFIRE_ARG_LINE = "argLine";

    @Override
    public void execute() {
        final String name = getEffectivePropertyName();
        final Properties projectProperties = getProject().getProperties();
        final String oldValue = projectProperties.getProperty(name);
        final AgentOptions agentOptions = buildAgentOptions();
        final String newValue = addVMArguments(oldValue, getAgentJarFile(), agentOptions);
        getLog().info(name + " set to " + newValue);
        projectProperties.setProperty(name, newValue);
    }

    private AgentOptions buildAgentOptions(){
        AgentOptions agentOptions = new AgentOptions();

        if (getTiaProjectDir() != null && !getTiaProjectDir().isEmpty()){
            agentOptions.setProjectDir(getTiaProjectDir());
        }

        if (getTiaDBFilePath() != null && !getTiaDBFilePath().isEmpty()){
            agentOptions.setDBFilePath(getTiaDBFilePath());
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

    public abstract MavenProject getProject();

    public abstract String getPropertyName();

    public abstract String getTiaProjectDir();

    public abstract String getTiaDBFilePath();
}
