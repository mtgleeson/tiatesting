package org.tiatesting.gradle.plugin;

import org.gradle.api.tasks.testing.Test;
import org.tiatesting.core.agent.SelectionHandoff;

/**
 * Spock: the {@code tia-spock} module's global extension reads the hand-off files named by test JVM
 * system properties.
 */
public class SpockFrameworkAdapter implements TestFrameworkAdapter {

    /** Name users select Spock by. */
    public static final String NAME = "spock";

    /**
     * @return {@code spock}
     */
    @Override
    public String name() {
        return NAME;
    }

    /**
     * @return {@code org.spockframework}
     */
    @Override
    public String dependencyGroup() {
        return "org.spockframework";
    }

    /**
     * @return {@code tia-spock}
     */
    @Override
    public String runtimeArtifactId() {
        return "tia-spock";
    }

    /**
     * Name the hand-off files in the system properties {@code TiaSpockGlobalExtension} reads. The
     * drain result property is only set when a drain result was written.
     *
     * @param testTask the test task whose forks receive the selection
     * @param handoff the written hand-off files
     */
    @Override
    public void handOffSelection(final Test testTask, final SelectionHandoff handoff) {
        testTask.systemProperty(SelectionHandoff.PROP_IGNORED_TESTS_FILE,
                handoff.getIgnoredTestsFile().getAbsolutePath());
        testTask.systemProperty(SelectionHandoff.PROP_SELECTED_TESTS_FILE,
                handoff.getSelectedTestsFile().getAbsolutePath());
        testTask.systemProperty(SelectionHandoff.PROP_SELECTION_DETAILS_FILE,
                handoff.getSelectionDetailsFile().getAbsolutePath());
        if (handoff.getDrainResultFile() != null) {
            testTask.systemProperty(SelectionHandoff.PROP_DRAIN_RESULT_FILE,
                    handoff.getDrainResultFile().getAbsolutePath());
        }
    }
}
