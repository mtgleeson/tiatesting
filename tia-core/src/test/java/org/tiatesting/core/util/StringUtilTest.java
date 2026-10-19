package org.tiatesting.core.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the configured comma-separated lists both build plugins parse, and the bounded name
 * sample that keeps project-sized log lines to a fixed length.
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

    /**
     * A collection no larger than the limit is listed in full, sorted.
     */
    @Test
    void aSampleWithinTheLimitIsListedInFullAndSorted() {
        // given
        Set<String> names = new HashSet<>(Arrays.asList("com.example.CTest", "com.example.ATest",
                "com.example.BTest"));

        // when
        String sample = StringUtil.sampleSorted(names, 3);

        // then
        assertEquals("[com.example.ATest, com.example.BTest, com.example.CTest]", sample);
    }

    /**
     * A collection over the limit lists the first names in sorted order and says how many it left
     * out.
     */
    @Test
    void aSampleOverTheLimitListsTheFirstSortedNamesAndCountsTheRest() {
        // given
        Set<String> names = new HashSet<>(Arrays.asList("e.ETest", "a.ATest", "d.DTest", "b.BTest",
                "c.CTest"));

        // when
        String sample = StringUtil.sampleSorted(names, 2);

        // then
        assertEquals("[a.ATest, b.BTest] and 3 more", sample);
    }

    /**
     * An empty collection renders as an empty list.
     */
    @Test
    void aSampleOfNothingRendersAsAnEmptyList() {
        // given
        Set<String> names = Collections.emptySet();

        // when
        String sample = StringUtil.sampleSorted(names, 10);

        // then
        assertEquals("[]", sample);
    }
}
