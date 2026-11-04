package org.tiatesting.core.util;

import org.junit.jupiter.api.Test;
import org.slf4j.event.EventRecodingLogger;
import org.slf4j.event.SubstituteLoggingEvent;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.SubstituteLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the sampled list logging: a short list is logged in full at INFO only, and a long one
 * is sampled at INFO with the full sorted list at DEBUG. Uses slf4j's recording logger, which has
 * every level enabled, so the DEBUG line can be checked as well as the INFO one.
 */
class LogUtilTest {

    private static final String MESSAGE = "Selected tests to run from VCS source changes";

    private final Queue<SubstituteLoggingEvent> events = new ConcurrentLinkedQueue<>();
    private final EventRecodingLogger log = new EventRecodingLogger(
            new SubstituteLogger("LogUtilTest", events, false), events);

    /**
     * A list at the sample size is logged in full at INFO, sorted, with no DEBUG line.
     */
    @Test
    void aListAtTheLimitIsLoggedInFullAtInfoOnly() {
        // given
        Set<String> names = new HashSet<>();
        for (int i = StringUtil.LOG_SAMPLE_SIZE - 1; i >= 0; i--) {
            names.add("com.example.Test" + i);
        }

        // when
        LogUtil.infoSampled(log, MESSAGE, names);

        // then
        List<String> expected = new ArrayList<>(names);
        Collections.sort(expected);
        assertEquals(Collections.singletonList("INFO " + MESSAGE + ": " + expected), recorded());
    }

    /**
     * A list over the sample size is sampled at INFO and logged in full, sorted, at DEBUG.
     */
    @Test
    void aListOverTheLimitIsSampledAtInfoAndListedInFullAtDebug() {
        // given
        Set<String> names = new HashSet<>();
        for (int i = 0; i < StringUtil.LOG_SAMPLE_SIZE + 2; i++) {
            names.add(String.format("com.example.Test%02d", i));
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);

        // when
        LogUtil.infoSampled(log, MESSAGE, names);

        // then
        assertEquals(Arrays.asList(
                "INFO " + MESSAGE + ": " + sorted.subList(0, StringUtil.LOG_SAMPLE_SIZE) + " and 2 more",
                "DEBUG " + MESSAGE + " (full list of " + sorted.size() + "): " + sorted), recorded());
    }

    /**
     * An empty list still logs its INFO line, so a source that selected nothing says so.
     */
    @Test
    void anEmptyListLogsAnEmptyInfoLine() {
        // given
        Set<String> names = Collections.emptySet();

        // when
        LogUtil.infoSampled(log, MESSAGE, names);

        // then
        assertEquals(Collections.singletonList("INFO " + MESSAGE + ": []"), recorded());
    }

    /**
     * @return each recorded event as {@code LEVEL formatted message}, in order.
     */
    private List<String> recorded() {
        List<String> lines = new ArrayList<>();
        for (SubstituteLoggingEvent event : events) {
            String message = MessageFormatter.arrayFormat(event.getMessage(), event.getArgumentArray()).getMessage();
            lines.add(event.getLevel() + " " + message);
        }
        return lines;
    }
}
