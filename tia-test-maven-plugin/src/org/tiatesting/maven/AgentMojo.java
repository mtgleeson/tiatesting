package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;

/**
 *
 * @phase initialize
 * @goal prepare-agent
 * @requiresProject true
 * @requiresDependencyResolution runtime
 * @threadSafe
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
     * @required
     * @readonly
     */
    Map<String, Artifact> pluginArtifactMap;
    /**
     * Allows to specify property which will contains settings for JaCoCo Agent.
     * If not specified, then "argLine" would be used for "jar" packaging and
     * "tycho.testArgLine" for "eclipse-test-plugin".
     *
     */
    String propertyName;

    /**
     * Maven project.
     *
     * @parameter property="project"
     * @readonly
     */
    private MavenProject project;

    @Override
    public void execute() {
        final String name = getEffectivePropertyName();
        System.out.println(project);
        /*
        final Properties projectProperties = project.getProperties();
        //final String oldValue = projectProperties.getProperty(name);
        final String newValue = format("-javaagent:%s", getAgentJarFile());
        getLog().info(name + " set to " + newValue);
        projectProperties.setProperty(name, newValue);
        */
    }

    File getAgentJarFile() {
        System.out.println(pluginArtifactMap);
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
