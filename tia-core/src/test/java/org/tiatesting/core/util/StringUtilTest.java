package org.tiatesting.core.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the configured comma-separated lists both build plugins parse.
 */
class StringUtilTest {

    @Test
    void entriesAreSplitAndTrimmed() {
        // given
        String csv = " /src/main/java, src/other ,/abs/path";

        // when
        List<String> entries = StringUtil.splitCsv(csv);

        // then
        assertEquals(Arrays.asList("/src/main/java", "src/other", "/abs/path"), entries);
    }

    @Test
    void anUnsetListStaysNull() {
        // given
        String csv = null;

        // when
        List<String> entries = StringUtil.splitCsv(csv);

        // then
        assertNull(entries);
    }
}
