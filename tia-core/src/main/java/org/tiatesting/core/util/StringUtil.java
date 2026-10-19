package org.tiatesting.core.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class StringUtil {

    /**
     * How many names a log line lists when it would otherwise list one per suite or class in the
     * project - see {@link #sampleSorted}.
     */
    public static final int LOG_SAMPLE_SIZE = 10;

    /**
     * Remove all trailing spaces and new lines for each element.
     *
     * @param inputStrings the input strings
     */
    public static void sanitizeInputArray(List<String> inputStrings) {
        if (inputStrings != null){
            inputStrings.replaceAll(s -> s.replace("\\R", "").trim());
        }
    }

    /**
     * Split a configured comma-separated list (directories, typically) into trimmed entries, the
     * same way for every build tool.
     *
     * @param csv the configured list; may be null
     * @return a new mutable list of the trimmed entries, or null when none is configured
     */
    public static List<String> splitCsv(String csv) {
        if (csv == null) {
            return null;
        }
        List<String> values = new ArrayList<>(Arrays.asList(csv.split(",")));
        sanitizeInputArray(values);
        return values;
    }

    /**
     * Render a bounded, deterministic sample of names for a log line: the first {@code limit}
     * names in sorted order, followed by how many were left out. Keeps a line that would otherwise
     * list every suite or class in the project - tens of kilobytes on a large one - to a fixed
     * size, while still naming a few for a spot check. It sorts the whole collection, so callers
     * build it only when the line will actually be logged.
     *
     * @param names the names to sample
     * @param limit the most names to list
     * @return the names as {@code [a, b, ...]}, with {@code " and N more"} appended when some were
     *         left out
     */
    public static String sampleSorted(Collection<String> names, int limit) {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        if (sorted.size() <= limit) {
            return sorted.toString();
        }
        return sorted.subList(0, limit) + " and " + (sorted.size() - limit) + " more";
    }
}
