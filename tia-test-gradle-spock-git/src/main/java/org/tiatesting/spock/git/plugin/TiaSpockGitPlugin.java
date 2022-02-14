package org.tiatesting.spock.git.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.testing.Test;

public class TiaSpockGitPlugin implements Plugin<Project> {

    private Project project;

    @Override
    public void apply(Project project) {
        this.project = project;
        TiaSpockGitPluginExtension tiaExtension = project.getExtensions().create("tia", TiaSpockGitPluginExtension.class);

        // apply the jacoco plugin
        project.getPluginManager().apply("jacoco");

        // add the plugin itself as a test dependency for the calling project. This is required so the Spock IGlobalExtension
        // file under META-INF is added to the test class path.
        DependencyHandler dependencies = project.getDependencies();
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, "org.tiatesting:tia-test-gradle-spock-git:1.1.0");

        applyToDefaultTasks(tiaExtension);
    }

    private void applyToDefaultTasks(TiaSpockGitPluginExtension extension) {
        project.getTasks().withType(Test.class).configureEach(extension::applyTo);
    }
}