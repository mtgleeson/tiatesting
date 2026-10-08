package org.tiatesting.gradle.plugin;

import org.gradle.api.tasks.Internal;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.Collections;

/**
 * Supplies the {@code -javaagent} argument that puts {@code tia-junit5-agent} on a JUnit 5 test
 * task's worker JVMs. Added to the task's {@code jvmArgumentProviders} from the task action, after
 * the jacoco plugin registered its own provider when the task was configured, so the Tia agent
 * follows the JaCoCo agent on the command line (jacoco/jacoco#551, the {@code $jacocoAccess}
 * ordering issue Maven handles the same way).
 */
public class TiaAgentArgumentProvider implements CommandLineArgumentProvider {

    private String argument;

    /**
     * @return the {@code -javaagent:<jar>=<options>} argument, or null before the selection is
     *         handed off. Internal: it names files in the task's temporary directory, which must not
     *         make the test task out of date.
     */
    @Internal
    public String getArgument() {
        return argument;
    }

    /**
     * @param argument the {@code -javaagent:<jar>=<options>} argument for this task execution
     */
    public void setArgument(final String argument) {
        this.argument = argument;
    }

    /**
     * @return the agent argument, or nothing when no selection was handed off
     */
    @Override
    public Iterable<String> asArguments() {
        return argument == null ? Collections.<String>emptyList() : Collections.singletonList(argument);
    }
}
