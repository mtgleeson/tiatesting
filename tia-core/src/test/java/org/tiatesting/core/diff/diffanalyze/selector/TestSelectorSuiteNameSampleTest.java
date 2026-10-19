package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests {@link TestSelector#sampleSuiteNames}, which bounds the "Ignoring" DEBUG line: the ignored
 * set is every tracked suite not selected, so logging it whole put tens of kilobytes on one line.
 */
class TestSelectorSuiteNameSampleTest {

    /**
     * A set no larger than the limit is listed in full, sorted.
     */
    @Test
    void setWithinTheLimitIsListedInFullAndSorted() {
        // given
        Set<String> names = new HashSet<>(Arrays.asList("com.example.CTest", "com.example.ATest",
                "com.example.BTest"));

        // when
        String sample = TestSelector.sampleSuiteNames(names, 3);

        // then
        assertEquals("[com.example.ATest, com.example.BTest, com.example.CTest]", sample);
    }

    /**
     * A set over the limit lists the first names in sorted order and says how many it left out.
     */
    @Test
    void setOverTheLimitListsTheFirstSortedNamesAndCountsTheRest() {
        // given
        Set<String> names = new HashSet<>(Arrays.asList("e.ETest", "a.ATest", "d.DTest", "b.BTest",
                "c.CTest"));

        // when
        String sample = TestSelector.sampleSuiteNames(names, 2);

        // then
        assertEquals("[a.ATest, b.BTest] and 3 more", sample);
    }

    /**
     * An empty set renders as an empty list.
     */
    @Test
    void emptySetRendersAsAnEmptyList() {
        // given
        Set<String> names = Collections.emptySet();

        // when
        String sample = TestSelector.sampleSuiteNames(names, 10);

        // then
        assertEquals("[]", sample);
    }
}
