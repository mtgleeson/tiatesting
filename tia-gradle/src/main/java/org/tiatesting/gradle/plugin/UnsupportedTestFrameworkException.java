package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;

/**
 * Detection found no test framework Tia supports in a project's declared test dependencies. Not a
 * build failure: the plugin warns and leaves that project's test tasks
 * running as they would without Tia - a library module that applies Tia only to stamp its publishes
 * is the common case.
 */
public class UnsupportedTestFrameworkException extends GradleException {

    /**
     * @param message why no supported framework was found, naming the setting that overrides it
     */
    public UnsupportedTestFrameworkException(final String message) {
        super(message);
    }
}
