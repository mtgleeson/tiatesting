package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.model.SelectionMode;

/**
 * Resolves the per-build runtime flags ({@code tiaSelectAllTests}, {@code tiaReseed}). A {@code -P}
 * project property wins over the extension, because these flags are meant to be set for one build
 * from the command line or a CI job rather than committed into the build script. See the "Forced
 * runs and re-seed" chapter in {@code WIKI.md}.
 */
public final class TiaRuntimeFlags {

    private TiaRuntimeFlags() {
    }

    /**
     * Resolve the selection mode for a test task or plan step.
     *
     * @param project the project whose {@code -P} properties are consulted
     * @param extension the task's populated Tia extension
     * @return the mode the flags ask for
     */
    public static SelectionMode selectionMode(Project project, TiaBaseTaskExtension extension) {
        return SelectionMode.fromFlags(
                resolve(project, extension.getSelectAllTests(), ForkSystemProperties.PROP_SELECT_ALL_TESTS),
                resolve(project, extension.getReseed(), ForkSystemProperties.PROP_RESEED));
    }

    /**
     * Resolve one boolean flag: the {@code -P} property when present, else the extension value,
     * else false.
     *
     * @param project the project whose properties are consulted
     * @param configured the extension's value, possibly null
     * @param propertyName the {@code -P} property name
     * @return the effective value
     */
    static boolean resolve(Project project, Boolean configured, String propertyName) {
        Object property = project.findProperty(propertyName);
        if (property != null) {
            return Boolean.parseBoolean(property.toString());
        }
        return Boolean.TRUE.equals(configured);
    }
}
