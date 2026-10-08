package org.tiatesting.core.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StringUtil {

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
}
