package org.tiatesting.core.coverage.client;

/**
 * Test fixture whose bytecode is instrumented and executed by {@link JacocoClientTest} to produce
 * genuine JaCoCo execution data. {@link #covered()} is invoked by the test; {@link #notCovered()} is
 * not, so it exercises the "method with no line coverage" path.
 */
public class CoverageSampleTarget {

    /**
     * Executed by the test to generate line coverage for this class.
     *
     * @return an arbitrary computed value
     */
    public int covered() {
        int x = 1;
        int y = 2;
        return x + y;
    }

    /**
     * Never executed by the test, so this method has no line coverage.
     *
     * @return an arbitrary value
     */
    public int notCovered() {
        int a = 10;
        return a * 2;
    }
}
