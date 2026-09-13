package org.tiatesting.junit.junit4;

/**
 * The JUnit 4 listener as registered for a Git project, through Surefire's {@code listener}
 * property. It adds nothing to {@link TiaJunit4Listener} beyond the name that property refers to:
 * the branch and the commit are resolved by the build JVM and republished into this JVM's system
 * properties, so nothing here opens a repository - which matters because Surefire constructs this
 * listener whether or not Tia is enabled for the run.
 */
public class TiaJunit4GitListener extends TiaJunit4Listener {

    /**
     * Construct the listener Surefire was pointed at.
     */
    public TiaJunit4GitListener() {
       super();
    }

}
