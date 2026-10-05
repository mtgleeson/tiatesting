package org.tiatesting.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.TestRunSelectionDetails;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the selection hand-off files round-trip: the suite lists and selection details read back
 * as written, the drain result file is only written when something was drained, and the library
 * JARs file holds one path per line.
 */
class SelectionHandoffTest {

    @TempDir
    Path tempDir;

    @Test
    void suiteListsAndSelectionDetailsReadBackAsWritten() {
        // given
        Set<String> ignored = new LinkedHashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest"));
        Set<String> selected = Collections.singleton("com.example.CTest");
        TestRunSelectionDetails details = new TestRunSelectionDetails(Collections.emptyList(), 1, 2, 3, 0, 0,
                SelectionMode.RESEED);

        // when
        SelectionHandoff handoff = SelectionHandoff.write(tempDir.resolve("tia").toFile(), ignored, selected,
                null, details);

        // then
        assertEquals(ignored, SelectionHandoff.readSuiteNames(handoff.getIgnoredTestsFile().getPath()));
        assertEquals(selected, SelectionHandoff.readSuiteNames(handoff.getSelectedTestsFile().getPath()));
        TestRunSelectionDetails readDetails = RunSelectionDetailsCodec.read(handoff.getSelectionDetailsFile());
        assertEquals(SelectionMode.RESEED, readDetails.getSelectionMode());
        assertEquals(2, readDetails.getNumNewTestFiles());
    }

    @Test
    void emptySuiteListsAreWrittenAsReadableEmptyFiles() {
        // given
        Set<String> none = Collections.emptySet();

        // when
        SelectionHandoff handoff = SelectionHandoff.write(tempDir.toFile(), none, none, null,
                TestRunSelectionDetails.empty());

        // then
        assertTrue(handoff.getIgnoredTestsFile().isFile());
        assertTrue(SelectionHandoff.readSuiteNames(handoff.getIgnoredTestsFile().getPath()).isEmpty());
    }

    @Test
    void drainResultFileIsOnlyWrittenWhenSomethingWasDrained() {
        // given
        LibraryImpactDrainResult nothingDrained = new LibraryImpactDrainResult();

        // when
        SelectionHandoff handoff = SelectionHandoff.write(tempDir.toFile(), Collections.emptySet(),
                Collections.emptySet(), nothingDrained, TestRunSelectionDetails.empty());

        // then
        assertNull(handoff.getDrainResultFile());
        assertFalse(new File(tempDir.toFile(), SelectionHandoff.DRAIN_RESULT_FILENAME).exists());
    }

    @Test
    void libraryJarsAreWrittenOnePathPerLine() throws IOException {
        // given
        List<String> jars = Arrays.asList("/libs/a.jar", "/libs/b.jar");

        // when
        File file = SelectionHandoff.writeLibraryJars(tempDir.toFile(), jars);

        // then
        assertEquals(SelectionHandoff.LIBRARY_JARS_FILENAME, file.getName());
        assertEquals(jars, Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void readSuiteNamesSkipsBlankLines() throws IOException {
        // given
        Path file = tempDir.resolve("suites.txt");
        Files.write(file, "com.example.ATest\n\n  \ncom.example.BTest\n".getBytes(StandardCharsets.UTF_8));

        // when
        Set<String> suites = SelectionHandoff.readSuiteNames(file.toString());

        // then
        assertEquals(new LinkedHashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest")), suites);
    }

    @Test
    void readSuiteNamesFailsNamingAMissingFile() {
        // given
        String missing = tempDir.resolve("missing.txt").toString();

        // when
        UncheckedIOException exception = assertThrows(UncheckedIOException.class,
                () -> SelectionHandoff.readSuiteNames(missing));

        // then
        assertTrue(exception.getMessage().contains(missing), exception.getMessage());
    }
}
