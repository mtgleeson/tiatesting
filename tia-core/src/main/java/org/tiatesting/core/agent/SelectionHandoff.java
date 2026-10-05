package org.tiatesting.core.agent;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.library.LibraryImpactDrainResultSerializer;
import org.tiatesting.core.model.TestRunSelectionDetails;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The files a build plugin writes after running test selection in the build JVM, for the forked
 * test JVM to read: the suites to ignore, the suites selected to run, the library-impact drain
 * result and the run's selection breakdown. Both build tools select in the build JVM and hand the
 * result over this way - Maven through the Tia javaagent's options, Gradle through test task system
 * properties naming the files. See the "How Tia exchanges data with the test runner" chapter in
 * {@code WIKI.md}.
 *
 * <p>{@link #write} produces an instance holding the written paths; the fork side reads them back
 * with {@link #readSuiteNames}, {@link LibraryImpactDrainResultSerializer#deserialize} and
 * {@link RunSelectionDetailsCodec#read}.
 */
public final class SelectionHandoff {

    /** File holding the suites the fork must skip, one per line. */
    public static final String IGNORED_TESTS_FILENAME = "ignored-tests.txt";

    /** File holding the suites selected to run, one per line. */
    public static final String SELECTED_TESTS_FILENAME = "selected-tests.txt";

    /** File holding the library JARs JaCoCo should load classes from, one path per line. */
    public static final String LIBRARY_JARS_FILENAME = "library-jars.txt";

    /** File holding the serialized library-impact drain result. */
    public static final String DRAIN_RESULT_FILENAME = "drain-result.ser";

    /** File holding the encoded run selection breakdown. */
    public static final String SELECTION_DETAILS_FILENAME = "run-selection-details.txt";

    /**
     * Fork system property naming the ignored-tests file, for a fork that reads the hand-off
     * itself rather than through the Tia javaagent (Gradle / Spock).
     */
    public static final String PROP_IGNORED_TESTS_FILE = "tiaIgnoredTestsFile";

    /** Fork system property naming the selected-tests file; see {@link #PROP_IGNORED_TESTS_FILE}. */
    public static final String PROP_SELECTED_TESTS_FILE = "tiaSelectedTestsFile";

    /** Fork system property naming the drain result file, absent when nothing was drained. */
    public static final String PROP_DRAIN_RESULT_FILE = "tiaDrainResultFile";

    /** Fork system property naming the run selection details file. */
    public static final String PROP_SELECTION_DETAILS_FILE = "tiaRunSelectionDetailsFile";

    private final File ignoredTestsFile;
    private final File selectedTestsFile;
    private final File drainResultFile;
    private final File selectionDetailsFile;

    /**
     * @param ignoredTestsFile the written ignored-tests file
     * @param selectedTestsFile the written selected-tests file
     * @param drainResultFile the written drain result file, or {@code null} when nothing was drained
     * @param selectionDetailsFile the written selection details file
     */
    private SelectionHandoff(final File ignoredTestsFile, final File selectedTestsFile,
                             final File drainResultFile, final File selectionDetailsFile) {
        this.ignoredTestsFile = ignoredTestsFile;
        this.selectedTestsFile = selectedTestsFile;
        this.drainResultFile = drainResultFile;
        this.selectionDetailsFile = selectionDetailsFile;
    }

    /**
     * Write a selection's hand-off files into a directory, creating it if needed. The drain result
     * file is only written when the drain result has drained batches; the other three are always
     * written, even when empty, since the fork needs a valid file to read.
     *
     * @param dir the directory to write into
     * @param testsToIgnore the suites the fork must skip
     * @param testsToRun the suites selected to run
     * @param drainResult the library-impact drain result, or {@code null}
     * @param selectionDetails the run's selection breakdown; must not be null
     * @return the written files
     */
    public static SelectionHandoff write(final File dir, final Set<String> testsToIgnore,
                                         final Set<String> testsToRun,
                                         final LibraryImpactDrainResult drainResult,
                                         final TestRunSelectionDetails selectionDetails) {
        File ignoredTestsFile = writeLines(new File(dir, IGNORED_TESTS_FILENAME), testsToIgnore);
        File selectedTestsFile = writeLines(new File(dir, SELECTED_TESTS_FILENAME), testsToRun);

        File drainResultFile = null;
        if (drainResult != null && drainResult.hasDrainedBatches()) {
            drainResultFile = new File(dir, DRAIN_RESULT_FILENAME);
            LibraryImpactDrainResultSerializer.serialize(drainResult, drainResultFile);
        }

        File selectionDetailsFile = new File(dir, SELECTION_DETAILS_FILENAME);
        RunSelectionDetailsCodec.write(selectionDetails, selectionDetailsFile);

        return new SelectionHandoff(ignoredTestsFile, selectedTestsFile, drainResultFile, selectionDetailsFile);
    }

    /**
     * Write the library JARs JaCoCo should load classes from, one path per line.
     *
     * @param dir the directory to write into
     * @param jarPaths the absolute JAR paths, in order
     * @return the written file
     */
    public static File writeLibraryJars(final File dir, final Collection<String> jarPaths) {
        return writeLines(new File(dir, LIBRARY_JARS_FILENAME), jarPaths);
    }

    /**
     * Read a suite-name file written by {@link #write}, one name per line, skipping blank lines.
     *
     * @param path the file path
     * @return the suite names, in file order
     * @throws UncheckedIOException if the file cannot be read
     */
    public static Set<String> readSuiteNames(final String path) {
        try (Stream<String> lines = Files.lines(new File(path).toPath(), StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.trim().isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("Tia could not read the test selection hand-off file " + path, e);
        }
    }

    /**
     * Write each value on its own line, creating the parent directory.
     *
     * @param file the file to write
     * @param values the lines
     * @return the written file
     * @throws UncheckedIOException if the file cannot be written
     */
    private static File writeLines(final File file, final Collection<String> values) {
        file.getParentFile().mkdirs();
        StringBuilder content = new StringBuilder();
        for (String value : values) {
            content.append(value).append(System.lineSeparator());
        }
        try {
            Files.write(file.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Tia could not write the test selection hand-off file " + file, e);
        }
        return file;
    }

    /**
     * @return the written ignored-tests file
     */
    public File getIgnoredTestsFile() {
        return ignoredTestsFile;
    }

    /**
     * @return the written selected-tests file
     */
    public File getSelectedTestsFile() {
        return selectedTestsFile;
    }

    /**
     * @return the written drain result file, or {@code null} when nothing was drained
     */
    public File getDrainResultFile() {
        return drainResultFile;
    }

    /**
     * @return the written selection details file
     */
    public File getSelectionDetailsFile() {
        return selectionDetailsFile;
    }
}
