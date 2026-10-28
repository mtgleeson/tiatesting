package org.tiatesting.core.diff.diffanalyze;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies what {@link MethodImpactAnalyzer} logs while matching a diff to tracked methods: one DEBUG
 * summary line per file and one DEBUG line per match, never a line per hunk and method pair, and the
 * tracked methods' widened ranges at TRACE, once per method however many hunks the file has.
 * slf4j-simple, the test runtime, fixes each logger's level when the logger is created and the test
 * JVM runs at INFO, so each test sets the analyzer's logger level by reflection for its duration. The
 * logger is looked up reflectively too, since slf4j-api is compile-only in this module; slf4j-simple
 * caches loggers by name, so this is the same instance the analyzer's own {@code log} field holds.
 * Output is captured from {@code System.err}, where slf4j-simple writes.
 */
class MethodImpactAnalyzerLogTest {

    /** slf4j-simple's numeric TRACE level ({@code LocationAwareLogger.TRACE_INT}). */
    private static final int TRACE_LEVEL = 0;
    /** slf4j-simple's numeric DEBUG level ({@code LocationAwareLogger.DEBUG_INT}). */
    private static final int DEBUG_LEVEL = 10;

    private static final String SOURCE_DIR = "/project/src/main/java";
    private static final String FILE_PATH = SOURCE_DIR + "/com/example/CarService.java";
    private static final String MAPPING_KEY = "com/example/CarService.java";

    private static final MethodImpactTracker CONSTRUCTOR_WITH_RANGES = new MethodImpactTracker(
            "com/example/CarService.<init>.()V", 8, 74,
            new int[]{7, 16, 20, 21, 25, 25, 34, 34, 39, 39, 43, 44, 49, 49, 53, 53, 57, 57, 66, 66, 70, 70, 74, 75});
    private static final MethodImpactTracker CHECK_BRAKES = new MethodImpactTracker(
            "com/example/CarService.checkBrakes.()V", 36, 38);

    private final MethodImpactAnalyzer analyzer = new MethodImpactAnalyzer();
    private Field levelField;
    private Object analyzerLogger;
    private int originalLevel;

    /**
     * Look up the analyzer's slf4j-simple logger and remember its level, so each test can raise it.
     *
     * @throws Exception if the logger or its level field cannot be reached
     */
    @BeforeEach
    void setUp() throws Exception {
        analyzerLogger = Class.forName("org.slf4j.LoggerFactory")
                .getMethod("getLogger", Class.class).invoke(null, MethodImpactAnalyzer.class);
        levelField = analyzerLogger.getClass().getDeclaredField("currentLogLevel");
        levelField.setAccessible(true);
        originalLevel = levelField.getInt(analyzerLogger);
    }

    /**
     * Restore the analyzer's logger level.
     *
     * @throws Exception if the level cannot be restored
     */
    @AfterEach
    void tearDown() throws Exception {
        levelField.setInt(analyzerLogger, originalLevel);
    }

    /**
     * At DEBUG, a file with two hunks and two tracked methods logs one summary line naming the hunk
     * ranges and the match count, one line for the one match, and nothing per hunk and method pair or
     * per tracked method.
     *
     * @throws Exception if the fixture cannot be read or the output decoded
     */
    @Test
    void debugLogsOneSummaryLineAndOneLinePerMatchOnly() throws Exception {
        // given
        levelField.setInt(analyzerLogger, DEBUG_LEVEL);
        List<String> revised = replaceLine(carServiceLines(), 37, "        System.out.println(\"check brakes 14\");");
        revised = replaceLine(revised, 72, "        System.out.println(\"temp 4 changed\");");

        // when
        AnalysisResult result = analyze(revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CHECK_BRAKES), result.impacted);
        assertTrue(result.log.contains(
                MAPPING_KEY + ": 2 hunk(s) [37-37, 72-72] against 2 tracked method(s), 1 matched"),
                "expected the per-file summary line, got: " + result.log);
        assertEquals(1, countOccurrences(result.log, "Found stored tracked method: "),
                "expected one line per match, got: " + result.log);
        assertFalse(result.log.contains("diffLineBegin"), "no line per hunk and method pair, got: " + result.log);
        assertFalse(result.log.contains("Tracked method "), "method ranges are TRACE only, got: " + result.log);
        assertFalse(result.log.contains("Diff hunk: "), "raw hunk headers are TRACE only, got: " + result.log);
    }

    /**
     * At TRACE, each tracked method's range is logged once even when several hunks are compared with
     * it, so the widening runs once per method; a method matched by both hunks is still counted once
     * in the summary.
     *
     * @throws Exception if the fixture cannot be read or the output decoded
     */
    @Test
    void traceLogsEachTrackedMethodRangeOnceForSeveralHunks() throws Exception {
        // given
        levelField.setInt(analyzerLogger, TRACE_LEVEL);
        List<String> revised = replaceLine(carServiceLines(), 36, "        new BrakePad().checkBrakePadsTwice();");
        revised = replaceLine(revised, 38, "    } ");

        // when
        AnalysisResult result = analyze(revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CHECK_BRAKES), result.impacted);
        assertEquals(1, countOccurrences(result.log,
                "Tracked method com/example/CarService.checkBrakes.()V, method line begin: 35, method line end: 39"),
                "expected the start-end method's widened range once, got: " + result.log);
        assertEquals(1, countOccurrences(result.log,
                "Tracked method com/example/CarService.<init>.()V, stored line ranges: "),
                "expected the exact-range method's ranges once, got: " + result.log);
        assertEquals(2, countOccurrences(result.log, "Diff hunk: "), "expected one line per hunk, got: " + result.log);
        assertEquals(2, countOccurrences(result.log, "Found stored tracked method: com/example/CarService.checkBrakes"),
                "expected one match line per matching hunk, got: " + result.log);
        assertTrue(result.log.contains(
                MAPPING_KEY + ": 2 hunk(s) [36-36, 38-38] against 2 tracked method(s), 1 matched"),
                "expected the per-file summary line, got: " + result.log);
    }

    /**
     * Holder for one analyzer run: the impacted method ids and everything logged during it.
     */
    private static final class AnalysisResult {
        private final Set<Integer> impacted;
        private final String log;

        /**
         * @param impacted the ids of the methods the diff impacts
         * @param log the output logged while analyzing
         */
        private AnalysisResult(Set<Integer> impacted, String log) {
            this.impacted = impacted;
            this.log = log;
        }
    }

    /**
     * Run the analyzer over the CarService fixture against a revised copy, capturing what it logs.
     *
     * @param revised the revised file lines
     * @param trackedMethods the tracked methods for the file
     * @return the impacted method ids and the captured log output
     * @throws Exception if the fixture cannot be read or the output decoded
     */
    private AnalysisResult analyze(List<String> revised, MethodImpactTracker... trackedMethods) throws Exception {
        Map<Integer, MethodImpactTracker> methodsForFile = new HashMap<>();
        for (MethodImpactTracker trackedMethod : trackedMethods) {
            methodsForFile.put(trackedMethod.hashCode(), trackedMethod);
        }
        Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile = new HashMap<>();
        methodsTrackedByFile.put(MAPPING_KEY, methodsForFile);

        Set<Integer> impacted = new HashSet<>();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));
        try {
            analyzer.getMethodsForImpactedFile(String.join("\n", carServiceLines()), String.join("\n", revised),
                    FILE_PATH, FILE_PATH, impacted, methodsTrackedByFile, Collections.singletonList(SOURCE_DIR));
        } finally {
            System.setErr(originalErr);
        }
        return new AnalysisResult(impacted, new String(captured.toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * Count the non-overlapping occurrences of a string in the captured output.
     *
     * @param text the captured output
     * @param fragment the string to count
     * @return the number of occurrences
     */
    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int from = text.indexOf(fragment);
        while (from >= 0) {
            count++;
            from = text.indexOf(fragment, from + fragment.length());
        }
        return count;
    }

    /**
     * Collect the ids the analyzer reports for the given methods.
     *
     * @param methods the expected impacted methods
     * @return their ids
     */
    private static Set<Integer> idsOf(MethodImpactTracker... methods) {
        Set<Integer> ids = new HashSet<>();
        for (MethodImpactTracker method : methods) {
            ids.add(method.hashCode());
        }
        return ids;
    }

    /**
     * Read the CarService fixture from the test resources.
     *
     * @return the fixture's lines, line 1 at index 0
     * @throws Exception if the resource can't be read
     */
    private List<String> carServiceLines() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/diffanalyze/CarService.java.txt");
             Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name())) {
            return new ArrayList<>(Arrays.asList(scanner.useDelimiter("\\A").next().split("\\R")));
        }
    }

    /**
     * Copy the lines with one line replaced.
     *
     * @param lines the original lines
     * @param lineNumber the 1-based line to replace
     * @param text the replacement text
     * @return the revised lines
     */
    private static List<String> replaceLine(List<String> lines, int lineNumber, String text) {
        List<String> revised = new ArrayList<>(lines);
        revised.set(lineNumber - 1, text);
        return revised;
    }
}
