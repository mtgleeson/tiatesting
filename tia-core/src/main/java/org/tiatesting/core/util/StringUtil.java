package org.tiatesting.core.util;

import java.util.List;

public class StringUtil {

    /**
     * Remove all trailing spaces and new lines for each element.
     *0.1.
     * @param inputStrings the input strings
     */
    public static void sanitizeInputArray(List<String> inputStrings) {
        if (inputStrings != null){
            inputStrings.replaceAll(s -> s.replace("\\R", "").trim());
        }
    }
}
