package org.tiatesting.gradle.plugin;

import org.gradle.api.tasks.testing.Test;
import org.tiatesting.core.agent.SelectionHandoff;

import java.util.Collections;
import java.util.List;

/**
 * The part of wiring Tia into a Gradle {@link Test} task that differs per test framework. Selection,
 * the distributed claim, the datastore and coverage settings are framework-agnostic and live in
 * {@link TiaTestTaskConfigurer}; an adapter names the Tia module that runs inside the test JVM for
 * its framework and hands the daemon's selection over to it.
 */
public interface TestFrameworkAdapter {

    /**
     * @return the framework name users select it by with {@code tia { testFramework = ... }}
     */
    String name();

    /**
     * Other names users can select this framework by with {@code tia { testFramework = ... }}, for a
     * framework that covers more than one version under one adapter.
     *
     * @return the alternative names, empty by default
     */
    default List<String> aliases() {
        return Collections.emptyList();
    }

    /**
     * The Maven group a project declares a dependency in to use this framework. Detection matches
     * declared dependencies against it without resolving any configuration.
     *
     * @return the group, e.g. {@code org.spockframework}
     */
    String dependencyGroup();

    /**
     * @return the artifact id of the Tia module the plugin adds to {@code testRuntimeOnly}
     */
    String runtimeArtifactId();

    /**
     * Hand the selection the daemon made to the forked test JVM(s), in the form this framework's Tia
     * module reads it.
     *
     * @param testTask the test task whose forks receive the selection
     * @param handoff the written hand-off files
     */
    void handOffSelection(Test testTask, SelectionHandoff handoff);
}
