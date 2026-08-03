package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class PendingLibraryForcedSelectionTest {

    @Test
    public void constructorPopulatesAllFields() {
        // given
        PendingLibraryForcedSelection forced = new PendingLibraryForcedSelection(
                "com.acme:widget", "1.2.0", 5L, "sql-run-all",
                StaticTestSelectionRuleMode.SUITE_NAMES, Arrays.asList("Repo.*", ".*IT"));

        // when / then
        assertEquals("com.acme:widget", forced.getGroupArtifact());
        assertEquals("1.2.0", forced.getStampVersion());
        assertEquals(5L, forced.getPublishSeq());
        assertEquals("sql-run-all", forced.getRuleName());
        assertEquals(StaticTestSelectionRuleMode.SUITE_NAMES, forced.getMode());
        assertEquals(Arrays.asList("Repo.*", ".*IT"), forced.getSuiteNamePatterns());
    }

    @Test
    public void nullPatternsBecomeEmptyList() {
        // given
        PendingLibraryForcedSelection forced = new PendingLibraryForcedSelection(
                "com.acme:widget", "1.2.0", 5L, "run-all",
                StaticTestSelectionRuleMode.RUN_ALL, null);

        // when / then
        assertEquals(Collections.emptyList(), forced.getSuiteNamePatterns());
    }

    @Test
    public void equalityKeyedOnGroupArtifactPublishSeqAndRuleName() {
        // given
        PendingLibraryForcedSelection a = new PendingLibraryForcedSelection(
                "com.acme:widget", "1.2.0", 5L, "r1", StaticTestSelectionRuleMode.RUN_ALL, null);
        PendingLibraryForcedSelection b = new PendingLibraryForcedSelection(
                "com.acme:widget", "9.9.9", 5L, "r1", StaticTestSelectionRuleMode.RUN_ALL, null);
        PendingLibraryForcedSelection c = new PendingLibraryForcedSelection(
                "com.acme:widget", "1.2.0", 5L, "r2", StaticTestSelectionRuleMode.RUN_ALL, null);

        // when / then
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
