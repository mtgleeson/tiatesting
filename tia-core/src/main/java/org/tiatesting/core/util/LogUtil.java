package org.tiatesting.core.util;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Helpers for log lines whose size would otherwise grow with the size of the project. See
 * "Logging lists of names" in the Logging conventions chapter of the WIKI.
 */
public final class LogUtil {

    private LogUtil() {
    }

    /**
     * Log a list of names at INFO as a bounded sample, and the full list at DEBUG when the sample
     * left some out. The INFO line reads {@code <message>: [a, b, ...] and N more} (see
     * {@link StringUtil#sampleSorted}), so a selection of every suite in a large project stays a
     * short line. A list of at most {@link StringUtil#LOG_SAMPLE_SIZE} names is logged in full at
     * INFO with no DEBUG line. The full list is sorted and built only when DEBUG is enabled.
     *
     * @param log the logger to write to
     * @param message the text before the list, e.g. {@code Selected tests to run from new test files}
     * @param names the names to log
     */
    public static void infoSampled(final Logger log, final String message, final Collection<String> names) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("{}: {}", message, StringUtil.sampleSorted(names, StringUtil.LOG_SAMPLE_SIZE));
        if (names.size() > StringUtil.LOG_SAMPLE_SIZE && log.isDebugEnabled()) {
            List<String> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            log.debug("{} (full list of {}): {}", message, sorted.size(), sorted);
        }
    }
}
