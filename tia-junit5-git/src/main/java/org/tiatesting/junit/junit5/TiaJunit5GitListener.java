package org.tiatesting.junit.junit5;

/**
 * The JUnit 5 listener as registered for a Git project. It adds nothing to {@link
 * TiaTestExecutionListener} beyond the name the service descriptor and the launcher session
 * listener refer to: the branch and the commit are resolved by the build JVM and republished into
 * this JVM's system properties, so nothing here opens a repository.
 */
public class TiaJunit5GitListener extends TiaTestExecutionListener {
    /**
     * @param sharedTestRunData the per-JVM state carried across Surefire retries
     */
    public TiaJunit5GitListener(final SharedTestRunData sharedTestRunData) {
        super(sharedTestRunData);
    }
}
