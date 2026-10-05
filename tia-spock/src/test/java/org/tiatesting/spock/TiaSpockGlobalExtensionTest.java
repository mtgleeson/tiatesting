package org.tiatesting.spock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.agent.SelectionHandoff;
import org.tiatesting.core.model.TestRunSelectionDetails;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Spock extension takes an ordinary build's selection from the hand-off files the
 * Gradle daemon wrote, rather than selecting itself: a suite in the ignored-tests file is skipped,
 * any other suite runs, and an enabled build handed no selection fails rather than running
 * everything silently. Mapping and history updates are off, so no datastore rows are touched.
 */
class TiaSpockGlobalExtensionTest {

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch", "tiaCommitValue",
            "tiaDBFilePath", SelectionHandoff.PROP_IGNORED_TESTS_FILE, SelectionHandoff.PROP_SELECTED_TESTS_FILE,
            SelectionHandoff.PROP_SELECTION_DETAILS_FILE, SelectionHandoff.PROP_DRAIN_RESULT_FILE
    };

    @TempDir
    File tempDir;

    private Map<String, String> savedProperties;

    /**
     * Save and clear the system properties these tests set, then configure an enabled build that
     * updates nothing, over an embedded database in the temp directory.
     */
    @BeforeEach
    void setUp() {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "false");
        System.setProperty("tiaUpdateDBTestRunHistory", "false");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");
        System.setProperty("tiaDBFilePath", tempDir.getAbsolutePath());
    }

    /**
     * Restore the system properties saved in {@link #setUp()}.
     */
    @AfterEach
    void tearDown() {
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    void suitesInTheIgnoredTestsHandoffAreSkippedAndOthersRun() {
        // given
        SelectionHandoff handoff = SelectionHandoff.write(new File(tempDir, "handoff"),
                Collections.singleton("com.example.SkippedSpec"), Collections.singleton("com.example.RunSpec"),
                null, TestRunSelectionDetails.empty());
        System.setProperty(SelectionHandoff.PROP_IGNORED_TESTS_FILE, handoff.getIgnoredTestsFile().getPath());
        System.setProperty(SelectionHandoff.PROP_SELECTED_TESTS_FILE, handoff.getSelectedTestsFile().getPath());
        System.setProperty(SelectionHandoff.PROP_SELECTION_DETAILS_FILE, handoff.getSelectionDetailsFile().getPath());
        SpecInfo skippedSpec = specInfo("SkippedSpec");
        SpecInfo runSpec = specInfo("RunSpec");

        // when
        TiaSpockGlobalExtension extension = new TiaSpockGlobalExtension();
        extension.visitSpec(skippedSpec);
        extension.visitSpec(runSpec);

        // then
        assertTrue(skippedSpec.isSkipped());
        assertFalse(runSpec.isSkipped());
    }

    @Test
    void anEnabledBuildHandedNoSelectionFails() {
        // given - no hand-off properties set

        // when
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                TiaSpockGlobalExtension::new);

        // then
        assertTrue(exception.getMessage().contains(SelectionHandoff.PROP_IGNORED_TESTS_FILE),
                exception.getMessage());
    }

    @Test
    void aDisabledBuildReadsNoHandoff() {
        // given
        System.setProperty("tiaEnabled", "false");
        SpecInfo spec = specInfo("AnySpec");

        // when
        TiaSpockGlobalExtension extension = new TiaSpockGlobalExtension();
        extension.visitSpec(spec);

        // then
        assertFalse(spec.isSkipped());
    }

    /**
     * @param name the spec's simple name
     * @return a spec in package {@code com.example}
     */
    private static SpecInfo specInfo(final String name) {
        SpecInfo specInfo = new SpecInfo();
        specInfo.setPackage("com.example");
        specInfo.setName(name);
        return specInfo;
    }
}
