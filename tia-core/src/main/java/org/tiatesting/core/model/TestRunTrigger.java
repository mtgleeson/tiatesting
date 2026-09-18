package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One selection trigger recorded for a test run: a source-code method whose change pulled tests
 * in, or a static selection rule that forced tests, together with the number of test suites that
 * trigger accounts for.
 *
 * <p>The count is a per-trigger contribution, not a partition of the run: a suite pulled in by two
 * changed methods is counted under each, so the counts across triggers can sum to more than the
 * run's distinct selected-suite count.
 */
public final class TestRunTrigger implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The kind of selection trigger a {@link TestRunTrigger} records. */
    public enum Type {
        /** A changed source method whose mapping pulled test suites into the run. */
        SOURCE_METHOD,
        /** A static test selection rule that forced test suites into the run. */
        STATIC_RULE
    }

    private final Type type;
    private final String name;
    private final int testCount;

    /**
     * @param type the kind of trigger
     * @param name the trigger's identity - a method id/name for {@link Type#SOURCE_METHOD}, a rule
     *             name for {@link Type#STATIC_RULE}
     * @param testCount the number of test suites this trigger accounts for
     */
    public TestRunTrigger(Type type, String name, int testCount) {
        this.type = type;
        this.name = name;
        this.testCount = testCount;
    }

    /** @return the kind of trigger */
    public Type getType() { return type; }

    /** @return the trigger's identity (method name or rule name) */
    public String getName() { return name; }

    /** @return the number of test suites this trigger accounts for */
    public int getTestCount() { return testCount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TestRunTrigger)) return false;
        TestRunTrigger that = (TestRunTrigger) o;
        return testCount == that.testCount && type == that.type && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() { return Objects.hash(type, name, testCount); }

    /**
     * Filter a trigger list to a single type and return the matches ordered by suite count, largest
     * first. Shared by every reader that ranks one type of trigger - the model's typed getters, the
     * HTML detail page and the console detail formatter - so the filter-and-sort rule lives in one
     * place.
     *
     * @param triggers the triggers to filter; null is tolerated and treated as empty
     * @param type the trigger type to keep
     * @return a new list of the matching triggers, highest suite count first
     */
    public static List<TestRunTrigger> filterByTypeSortedByCountDesc(List<TestRunTrigger> triggers,
                                                                     Type type) {
        List<TestRunTrigger> filtered = new ArrayList<>();
        if (triggers != null) {
            for (TestRunTrigger t : triggers) {
                if (t.getType() == type) {
                    filtered.add(t);
                }
            }
        }
        filtered.sort(Comparator.comparingInt(TestRunTrigger::getTestCount).reversed());
        return filtered;
    }
}
