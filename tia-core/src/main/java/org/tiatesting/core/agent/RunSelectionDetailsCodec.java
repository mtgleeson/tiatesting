package org.tiatesting.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a {@link TestRunSelectionDetails} breakdown to a small tab-separated sidecar text
 * file and reads it back. This is how the breakdown is carried from the build JVM - where test
 * selection runs and the breakdown is computed - to the forked test JVM - where the history row
 * is written - the same way the ignore-tests list is already passed by file; see the "Run history
 * details" chapter in {@code WIKI.md}.
 *
 * <p>File format, UTF-8, one record per line:
 * <pre>
 * counters&lt;TAB&gt;modified&lt;TAB&gt;new&lt;TAB&gt;prevFailed&lt;TAB&gt;unsealed&lt;TAB&gt;pendingLib
 * trigger&lt;TAB&gt;SOURCE_METHOD&lt;TAB&gt;&lt;count&gt;&lt;TAB&gt;&lt;name&gt;
 * trigger&lt;TAB&gt;STATIC_RULE&lt;TAB&gt;&lt;count&gt;&lt;TAB&gt;&lt;name&gt;
 * </pre>
 * The trigger name is always the last field on its line, so it may contain any character except a
 * newline or a tab; a tab (the field delimiter) or a newline (the record delimiter) embedded in a
 * name is replaced with a single space on write, since either would otherwise corrupt the record
 * on read.
 */
public final class RunSelectionDetailsCodec {

    private static final Logger log = LoggerFactory.getLogger(RunSelectionDetailsCodec.class);

    private static final String COUNTERS_PREFIX = "counters";
    private static final String TRIGGER_PREFIX = "trigger";

    private RunSelectionDetailsCodec() {
    }

    /**
     * Write a {@link TestRunSelectionDetails} breakdown to {@code file} as UTF-8 text: a counters
     * line followed by one line per trigger, in the order returned by {@link
     * TestRunSelectionDetails#getTriggers()}. Any missing parent directories of {@code file} are
     * created first, matching how the ignore-tests sidecar file is written.
     *
     * @param details the breakdown to serialize; must not be null
     * @param file the sidecar file to write, overwriting any existing content
     * @throws UncheckedIOException if the parent directories or the file cannot be created or
     *                              written; wraps the underlying {@link IOException} since the
     *                              build-side caller has no meaningful per-line recovery here
     */
    public static void write(TestRunSelectionDetails details, File file) {
        StringBuilder sb = new StringBuilder();
        sb.append(COUNTERS_PREFIX).append('\t')
                .append(details.getNumModifiedTestFiles()).append('\t')
                .append(details.getNumNewTestFiles()).append('\t')
                .append(details.getNumPreviouslyFailed()).append('\t')
                .append(details.getNumUnsealedMapping()).append('\t')
                .append(details.getNumPendingLibrary()).append('\n');

        for (TestRunTrigger trigger : details.getTriggers()) {
            String safeName = trigger.getName() == null ? ""
                    : trigger.getName().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            sb.append(TRIGGER_PREFIX).append('\t')
                    .append(trigger.getType().name()).append('\t')
                    .append(trigger.getTestCount()).append('\t')
                    .append(safeName).append('\n');
        }

        try {
            Path path = file.toPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write run selection details file: " + file, e);
        }
    }

    /**
     * Read a {@link TestRunSelectionDetails} breakdown back from a sidecar file previously written
     * by {@link #write(TestRunSelectionDetails, File)}. Parsing is defensive: a missing or blank
     * file yields {@link TestRunSelectionDetails#empty()}, and any line that cannot be parsed -
     * an unknown trigger type, a non-integer count, or a line with too few fields - is skipped
     * (logged at debug) rather than failing the whole read, since a partially-corrupted sidecar
     * file should still surface the records it does contain to the forked JVM writing the history
     * row.
     *
     * @param file the sidecar file to read; may not exist
     * @return the parsed breakdown, or {@link TestRunSelectionDetails#empty()} if {@code file}
     *         does not exist or has no content
     */
    public static TestRunSelectionDetails read(File file) {
        if (file == null || !file.exists()) {
            return TestRunSelectionDetails.empty();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Failed to read run selection details file: {}", file, e);
            return TestRunSelectionDetails.empty();
        }

        int numModifiedTestFiles = 0;
        int numNewTestFiles = 0;
        int numPreviouslyFailed = 0;
        int numUnsealedMapping = 0;
        int numPendingLibrary = 0;
        List<TestRunTrigger> triggers = new ArrayList<>();
        boolean sawAnyContent = false;

        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            sawAnyContent = true;

            if (line.startsWith(COUNTERS_PREFIX + "\t")) {
                String[] fields = line.split("\t");
                numModifiedTestFiles = parseIntOrZero(fields, 1);
                numNewTestFiles = parseIntOrZero(fields, 2);
                numPreviouslyFailed = parseIntOrZero(fields, 3);
                numUnsealedMapping = parseIntOrZero(fields, 4);
                numPendingLibrary = parseIntOrZero(fields, 5);
            } else if (line.startsWith(TRIGGER_PREFIX + "\t")) {
                TestRunTrigger trigger = parseTriggerLine(line);
                if (trigger != null) {
                    triggers.add(trigger);
                }
            } else {
                log.debug("Skipping unrecognized run selection details line: {}", line);
            }
        }

        if (!sawAnyContent) {
            return TestRunSelectionDetails.empty();
        }

        return new TestRunSelectionDetails(triggers, numModifiedTestFiles, numNewTestFiles,
                numPreviouslyFailed, numUnsealedMapping, numPendingLibrary);
    }

    /**
     * Parse one {@code trigger}-prefixed line into a {@link TestRunTrigger}, tolerating an unknown
     * type token, a non-integer count, or too few fields by returning null so the caller can skip
     * the line instead of failing the whole read.
     *
     * @param line the raw line, already known to start with {@code "trigger\t"}
     * @return the parsed trigger, or null if the line could not be parsed
     */
    private static TestRunTrigger parseTriggerLine(String line) {
        String[] fields = line.split("\t", 4);
        if (fields.length < 4) {
            log.debug("Skipping malformed trigger line (too few fields): {}", line);
            return null;
        }

        TestRunTrigger.Type type;
        try {
            type = TestRunTrigger.Type.valueOf(fields[1]);
        } catch (IllegalArgumentException e) {
            log.debug("Skipping trigger line with unknown type '{}': {}", fields[1], line);
            return null;
        }

        int count;
        try {
            count = Integer.parseInt(fields[2]);
        } catch (NumberFormatException e) {
            log.debug("Skipping trigger line with non-integer count '{}': {}", fields[2], line);
            return null;
        }

        return new TestRunTrigger(type, fields[3], count);
    }

    /**
     * Read one integer counter out of a split counters line, defaulting to zero when the line was
     * too short to contain that field or the field is not a valid integer.
     *
     * @param fields the counters line split on tab
     * @param index the field index to read
     * @return the parsed integer, or zero if the field is missing or unparseable
     */
    private static int parseIntOrZero(String[] fields, int index) {
        if (index >= fields.length) {
            return 0;
        }
        try {
            return Integer.parseInt(fields[index]);
        } catch (NumberFormatException e) {
            log.debug("Treating non-integer counter field '{}' as zero", fields[index]);
            return 0;
        }
    }
}
