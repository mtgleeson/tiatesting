package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.tasks.testing.Test;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.agent.SelectionHandoff;

import java.io.File;

/**
 * JUnit 5: the selection reaches the worker JVM through {@code tia-junit5-agent}, as on Maven. The
 * agent's {@code premain} reads the hand-off files named in its options, marks the ignored suites
 * {@code @Disabled} so they report as skipped, and publishes the rest as system properties for the
 * {@code tia-junit5} listener. The other settings already arrive as test task system properties, so
 * the agent's fork-properties and library-jars options stay unset. See the "How Tia exchanges data
 * with the test runner" chapter in {@code WIKI.md}.
 */
public class Junit5FrameworkAdapter implements TestFrameworkAdapter {

    /** Name users select JUnit 5 by. */
    public static final String NAME = "junit5";

    /** Group of the Tia agent artifact. */
    static final String AGENT_GROUP = "org.tiatesting";

    /** Artifact id of the Tia JUnit 5 agent. */
    static final String AGENT_ARTIFACT_ID = "tia-junit5-agent";

    /** Classifier of the agent's self-contained jar, the one put on {@code -javaagent}. */
    static final String AGENT_CLASSIFIER = "runtime";

    /*
    The resolved agent jar, kept for the build: an adapter belongs to one project's plugin, and every
    JUnit 5 test task in that project hands off with the same jar.
     */
    private File agentJar;

    /**
     * The name users select JUnit 5 by in {@code tia { testFramework = ... }}.
     *
     * @return {@code junit5}
     */
    @Override
    public String name() {
        return NAME;
    }

    /**
     * The group of JUnit Jupiter, whose presence among a project's declared test dependencies makes
     * detection pick JUnit 5 (unless Spock is declared too).
     *
     * @return {@code org.junit.jupiter}
     */
    @Override
    public String dependencyGroup() {
        return "org.junit.jupiter";
    }

    /**
     * The Tia module the plugin adds to {@code testRuntimeOnly}: its launcher session listener
     * records coverage and the run in the test JVM.
     *
     * @return {@code tia-junit5}
     */
    @Override
    public String runtimeArtifactId() {
        return "tia-junit5";
    }

    /**
     * Put the Tia agent on the test task's worker JVMs with options naming the hand-off files. The
     * argument is carried by a {@link TiaAgentArgumentProvider} appended to the task's
     * {@code jvmArgumentProviders} the first time, and updated in place on a later execution, so it
     * always follows the JaCoCo agent's provider, which the jacoco plugin registered when the task
     * was configured.
     *
     * @param testTask the test task whose forks receive the selection
     * @param handoff the written hand-off files
     */
    @Override
    public void handOffSelection(final Test testTask, final SelectionHandoff handoff) {
        String argument = "-javaagent:" + agentJar(testTask.getProject()).getAbsolutePath() + "="
                + agentOptions(handoff).toCommandLineOptionsString();
        agentArgumentProvider(testTask).setArgument(argument);
    }

    /**
     * Build the agent options for the hand-off files. The drain result is named only when one was
     * written.
     *
     * @param handoff the written hand-off files
     * @return the agent options
     */
    static AgentOptions agentOptions(final SelectionHandoff handoff) {
        AgentOptions agentOptions = new AgentOptions();
        agentOptions.setIgnoreTestsFile(handoff.getIgnoredTestsFile().getAbsolutePath());
        agentOptions.setSelectedTestsFile(handoff.getSelectedTestsFile().getAbsolutePath());
        agentOptions.setSelectionDetailsFile(handoff.getSelectionDetailsFile().getAbsolutePath());
        if (handoff.getDrainResultFile() != null) {
            agentOptions.setDrainResultFile(handoff.getDrainResultFile().getAbsolutePath());
        }
        return agentOptions;
    }

    /**
     * Find the task's Tia agent argument provider, appending one when the task has none yet.
     *
     * @param testTask the test task
     * @return the task's Tia agent argument provider
     */
    static TiaAgentArgumentProvider agentArgumentProvider(final Test testTask) {
        return testTask.getJvmArgumentProviders().stream()
                .filter(TiaAgentArgumentProvider.class::isInstance)
                .map(TiaAgentArgumentProvider.class::cast)
                .findFirst()
                .orElseGet(() -> {
                    TiaAgentArgumentProvider provider = new TiaAgentArgumentProvider();
                    testTask.getJvmArgumentProviders().add(provider);
                    return provider;
                });
    }

    /**
     * The agent jar, resolved the first time a test task of this project hands off and reused by the
     * project's other test tasks.
     *
     * @param project the project whose repositories the agent is resolved from
     * @return the agent jar
     */
    private synchronized File agentJar(final Project project) {
        if (agentJar == null) {
            agentJar = resolveAgentJar(project);
        }
        return agentJar;
    }

    /**
     * Resolve the agent's self-contained jar at this plugin's version from the project's
     * repositories, in a detached configuration so it never reaches any of the project's classpaths.
     * Non-transitive: the jar bundles what the agent needs.
     *
     * @param project the project whose repositories the agent is resolved from
     * @return the agent jar
     */
    private static File resolveAgentJar(final Project project) {
        Dependency agent = project.getDependencies().create(AGENT_GROUP + ":" + AGENT_ARTIFACT_ID + ":"
                + TiaVersion.get() + ":" + AGENT_CLASSIFIER);
        ((ModuleDependency) agent).setTransitive(false);
        Configuration configuration = project.getConfigurations().detachedConfiguration(agent);
        return configuration.getSingleFile();
    }
}
