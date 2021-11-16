package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;

/**
 *
 * @goal prepare-agent
 * @requiresDependencyResolution compile
 */
public class AgentMojo extends AbstractMojo {

    /**
     * Name of the JaCoCo Agent artifact.
     */
    static final String AGENT_ARTIFACT_NAME = "org.tiatesting:tia-test-plugin-agent";

    /**
     * Name of the property used in maven-surefire-plugin.
     */
    static final String SUREFIRE_ARG_LINE = "argLine";

    /**
     * Map of plugin artifacts.
     *
     * @parameter expression="${plugin.artifactMap}"
     * @required
     * @readonly
     */
    protected Map<String, Artifact> pluginArtifactMap;

    /**
     * Allows us to specify property which will contains settings for JaCoCo Agent.
     * If not specified, then "argLine" would be used for "jar" packaging and
     * "tycho.testArgLine" for "eclipse-test-plugin".
     *
     */
    String propertyName;

    /**
     * @parameter default-value="${project}"
     * @readonly
     */
    private MavenProject project;

    @Override
    public void execute() {
        final String name = getEffectivePropertyName();
        final Properties projectProperties = project.getProperties();
        final String oldValue = projectProperties.getProperty(name);
        final String newValue = prependVMArguments(oldValue, getAgentJarFile());
        getLog().info(name + " set to " + newValue);
        projectProperties.setProperty(name, newValue);
    }

    public String prependVMArguments(final String arguments,
                                     final File agentJarFile) {
        final List<String> args = CommandLineSupport.split(arguments);
        final String plainAgent = format("-javaagent:%s", agentJarFile);
        for (final Iterator<String> i = args.iterator(); i.hasNext();) {
            if (i.next().startsWith(plainAgent)) {
                i.remove();
            }
        }
        args.add(0, format("-javaagent:%s", agentJarFile));
        return CommandLineSupport.quote(args);
    }

    File getAgentJarFile() {
        final Artifact jacocoAgentArtifact = pluginArtifactMap.get(AGENT_ARTIFACT_NAME);
        return jacocoAgentArtifact.getFile();
    }

    String getEffectivePropertyName() {
        if (isPropertyNameSpecified()) {
            return propertyName;
        }
        return SUREFIRE_ARG_LINE;
    }

    boolean isPropertyNameSpecified() {
        return propertyName != null && !"".equals(propertyName);
    }

}
