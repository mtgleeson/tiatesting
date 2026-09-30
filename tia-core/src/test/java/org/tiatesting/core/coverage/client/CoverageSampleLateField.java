package org.tiatesting.core.coverage.client;

/**
 * Test fixture for {@link JacocoClientTest}: a field declared after a method, which the compiler
 * folds into the constructor and so stretches the constructor's start-end line range over
 * {@link #laterMethod()}.
 */
public class CoverageSampleLateField {

    private int first = 1;

    /**
     * Executed by the test when the instance is created.
     */
    public CoverageSampleLateField() {
        first++;
    }

    /**
     * A method declared between the constructor and the late field.
     *
     * @return the first field's value
     */
    public int laterMethod() {
        return first + last;
    }

    private int last = 2;
}
