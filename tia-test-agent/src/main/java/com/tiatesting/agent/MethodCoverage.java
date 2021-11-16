package com.tiatesting.agent;

import java.util.LinkedHashSet;
import java.util.Set;

public class MethodCoverage {
    private static Set<String> referencedClasses = new LinkedHashSet<>();

    /**
     * @return the list of referenced classes during a program/test execution, which has been
     * resolved by instrumentation techniques
     */
    public static Set<String> getReferencedClasses() {
        return referencedClasses;
    }

    /**
     * Cleans the list of referenced classes. It is needed for clean the list of
     * referenced classes between tests.
     */
    public static void cleanUp() {
        referencedClasses = new LinkedHashSet<>();
    }
}
