package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.agent.SelectionHandoff;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.TestRunSelectionDetails;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the JUnit 5 adapter's hand-off: the agent options name the hand-off files, and the agent
 * argument is carried by one provider placed after the JaCoCo agent's on the test task. The jar
 * resolution itself is exercised end to end in the {@code junit5-git-gradle} fixture.
 */
class Junit5FrameworkAdapterTest {

    @org.junit.jupiter.api.Test
    void agentOptionsNameTheHandoffFiles(@TempDir File tempDir) {
        // given
        SelectionHandoff handoff = SelectionHandoff.write(tempDir, Collections.singleton("com.example.ATest"),
                Collections.singleton("com.example.BTest"), null, TestRunSelectionDetails.empty());

        // when
        AgentOptions options = Junit5FrameworkAdapter.agentOptions(handoff);

        // then
        assertEquals(handoff.getIgnoredTestsFile().getAbsolutePath(), options.getIgnoreTestsFile());
        assertEquals(handoff.getSelectedTestsFile().getAbsolutePath(), options.getSelectedTestsFile());
        assertEquals(handoff.getSelectionDetailsFile().getAbsolutePath(), options.getSelectionDetailsFile());
        // unset options read back as empty: the agent skips them
        assertTrue(isUnset(options.getDrainResultFile()), options.getDrainResultFile());
        assertTrue(isUnset(options.getForkPropertiesFile()), options.getForkPropertiesFile());
        assertTrue(isUnset(options.getLibraryJarsFile()), options.getLibraryJarsFile());
    }

    @org.junit.jupiter.api.Test
    void agentOptionsNameADrainResultWhenOneWasWritten(@TempDir File tempDir) {
        // given
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:lib", 1L);
        SelectionHandoff handoff = SelectionHandoff.write(tempDir, Collections.<String>emptySet(),
                Collections.<String>emptySet(), drainResult, TestRunSelectionDetails.empty());

        // when
        AgentOptions options = Junit5FrameworkAdapter.agentOptions(handoff);

        // then
        assertEquals(handoff.getDrainResultFile().getAbsolutePath(), options.getDrainResultFile());
    }

    @org.junit.jupiter.api.Test
    void agentProviderFollowsTheJacocoAgentProvider(@TempDir File projectDir) {
        // given - jacoco registers its agent provider when it configures the test task
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("jacoco");
        Test testTask = (Test) project.getTasks().getByName("test");

        // when
        TiaAgentArgumentProvider provider = Junit5FrameworkAdapter.agentArgumentProvider(testTask);

        // then
        List<CommandLineArgumentProvider> providers = new ArrayList<>();
        testTask.getJvmArgumentProviders().forEach(providers::add);
        int jacocoIndex = -1;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).getClass().getName().toLowerCase().contains("jacoco")) {
                jacocoIndex = i;
            }
        }
        assertTrue(jacocoIndex >= 0, "the jacoco plugin registered no agent provider: " + providers);
        assertTrue(providers.indexOf(provider) > jacocoIndex, providers.toString());
    }

    @org.junit.jupiter.api.Test
    void aSecondHandoffReusesTheTasksProvider(@TempDir File projectDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        Test testTask = (Test) project.getTasks().getByName("test");
        TiaAgentArgumentProvider first = Junit5FrameworkAdapter.agentArgumentProvider(testTask);

        // when
        TiaAgentArgumentProvider second = Junit5FrameworkAdapter.agentArgumentProvider(testTask);

        // then
        assertSame(first, second);
        long tiaProviders = testTask.getJvmArgumentProviders().stream()
                .filter(TiaAgentArgumentProvider.class::isInstance).count();
        assertEquals(1, tiaProviders);
    }

    @org.junit.jupiter.api.Test
    void providerContributesNothingUntilAnArgumentIsSet() {
        // given
        TiaAgentArgumentProvider provider = new TiaAgentArgumentProvider();

        // when
        Iterable<String> before = provider.asArguments();
        provider.setArgument("-javaagent:tia.jar=ignoreTestsFile=a");
        Iterable<String> after = provider.asArguments();

        // then
        assertTrue(!before.iterator().hasNext());
        assertEquals(Collections.singletonList("-javaagent:tia.jar=ignoreTestsFile=a"), after);
    }

    /**
     * @param option an agent option value
     * @return true when the option is unset, which {@link AgentOptions} reports as null or empty
     */
    private static boolean isUnset(final String option) {
        return option == null || option.isEmpty();
    }
}
