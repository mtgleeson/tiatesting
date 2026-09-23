package org.tiatesting.core.coverage.client;

/**
 * Test fixture that is present in the compiled-classes directory but never executed by
 * {@link JacocoClientTest}. Used to assert that scoped analysis does not leak coverage for classes
 * absent from the dumped execution data.
 */
public class CoverageSampleOther {

    /**
     * A method that is never invoked during the test.
     *
     * @return an arbitrary value
     */
    public int doStuff() {
        int z = 7;
        return z + 1;
    }
}
