package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.agent.SelectionHandoff;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.TestRunSelectionDetails;

import java.io.File;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the Spock adapter names the hand-off files in the system properties
 * {@code TiaSpockGlobalExtension} reads, leaving the drain result property unset when nothing was
 * drained.
 */
class SpockFrameworkAdapterTest {

    @org.junit.jupiter.api.Test
    void handOffNamesTheSelectionFilesInSystemProperties(@TempDir File projectDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        Test testTask = (Test) project.getTasks().getByName("test");
        SelectionHandoff handoff = SelectionHandoff.write(new File(projectDir, "handoff"),
                Collections.singleton("com.example.ASpec"), Collections.emptySet(),
                new LibraryImpactDrainResult(), TestRunSelectionDetails.empty());

        // when
        new SpockFrameworkAdapter().handOffSelection(testTask, handoff);

        // then
        Map<String, Object> properties = testTask.getSystemProperties();
        assertEquals(handoff.getIgnoredTestsFile().getAbsolutePath(),
                properties.get(SelectionHandoff.PROP_IGNORED_TESTS_FILE));
        assertEquals(handoff.getSelectedTestsFile().getAbsolutePath(),
                properties.get(SelectionHandoff.PROP_SELECTED_TESTS_FILE));
        assertEquals(handoff.getSelectionDetailsFile().getAbsolutePath(),
                properties.get(SelectionHandoff.PROP_SELECTION_DETAILS_FILE));
        assertFalse(properties.containsKey(SelectionHandoff.PROP_DRAIN_RESULT_FILE));
    }
}
