package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestSuiteTracker;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SuiteNameIndex}.
 */
class SuiteNameIndexTest {

    @Test
    void simpleNameOfStripsPackagePrefix() {
        // given / when / then
        assertEquals("OrderServiceIT", SuiteNameIndex.simpleNameOf("com.acme.OrderServiceIT"));
    }

    @Test
    void simpleNameOfReturnsFullNameWhenNoDot() {
        // given / when / then
        assertEquals("OrderServiceIT", SuiteNameIndex.simpleNameOf("OrderServiceIT"));
    }

    @Test
    void emptyTrackedSuitesProducesEmptyIndex() {
        // given
        SuiteNameIndex index = new SuiteNameIndex(trackedWith());

        // when
        Map<String, List<String>> simpleMap = index.getSimpleNameToFqns();
        Set<String> fqns = index.getFqns();

        // then
        assertTrue(simpleMap.isEmpty());
        assertTrue(fqns.isEmpty());
    }

    @Test
    void nullTrackedSuitesProducesEmptyIndex() {
        // given
        SuiteNameIndex index = new SuiteNameIndex(null);

        // when / then
        assertTrue(index.getSimpleNameToFqns().isEmpty());
        assertTrue(index.getFqns().isEmpty());
    }

    @Test
    void indexCapturesFqnsForEveryTrackedSuite() {
        // given
        SuiteNameIndex index = new SuiteNameIndex(
                trackedWith("com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec"));

        // when
        Set<String> fqns = index.getFqns();

        // then
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec"), new HashSet<>(fqns));
    }

    @Test
    void simpleNameMapGroupsSuitesSharingASimpleClassName() {
        // given - two suites in different packages with the same simple name
        SuiteNameIndex index = new SuiteNameIndex(
                trackedWith("com.acme.OrderServiceIT", "com.other.OrderServiceIT", "com.acme.PaymentServiceSpec"));

        // when
        Map<String, List<String>> simpleMap = index.getSimpleNameToFqns();

        // then
        assertEquals(setOf("com.acme.OrderServiceIT", "com.other.OrderServiceIT"),
                new HashSet<>(simpleMap.get("OrderServiceIT")));
        assertEquals(Arrays.asList("com.acme.PaymentServiceSpec"), simpleMap.get("PaymentServiceSpec"));
    }

    @Test
    void getSimpleNameToFqnsCachesAcrossCalls() {
        // given
        SuiteNameIndex index = new SuiteNameIndex(trackedWith("com.acme.OrderServiceIT"));

        // when
        Map<String, List<String>> firstCall = index.getSimpleNameToFqns();
        Map<String, List<String>> secondCall = index.getSimpleNameToFqns();

        // then - same reference; the index does not rebuild on subsequent calls
        assertSame(firstCall, secondCall);
    }

    private static Map<String, TestSuiteTracker> trackedWith(String... suiteNames) {
        Map<String, TestSuiteTracker> tracked = new LinkedHashMap<>();
        for (String name : suiteNames) {
            tracked.put(name, new TestSuiteTracker(name));
        }
        return tracked;
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
