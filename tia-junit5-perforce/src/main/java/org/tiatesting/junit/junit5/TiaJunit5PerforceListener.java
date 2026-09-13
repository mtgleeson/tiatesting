package org.tiatesting.junit.junit5;

/**
 * The JUnit 5 listener as registered for a Perforce project. It adds nothing to {@link
 * TiaTestExecutionListener} beyond the name the service descriptor and the launcher session
 * listener refer to: the branch and the changelist are resolved by the build JVM and republished
 * into this JVM's system properties, so nothing here opens a Perforce connection.
 */
public class TiaJunit5PerforceListener extends TiaTestExecutionListener {
    /**
     * @param sharedTestRunData the per-JVM state carried across Surefire retries
     */
    public TiaJunit5PerforceListener(final SharedTestRunData sharedTestRunData) {
        super(sharedTestRunData);
    }
}
