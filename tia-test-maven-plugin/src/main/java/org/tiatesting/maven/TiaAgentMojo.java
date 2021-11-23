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
}
