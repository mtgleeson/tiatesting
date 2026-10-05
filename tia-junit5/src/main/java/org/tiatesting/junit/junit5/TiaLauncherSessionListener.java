package org.tiatesting.junit.junit5;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Entry point JUnit Platform discovers through this module's {@code META-INF/services}
 * descriptor. Registers Tia's {@link TiaTestExecutionListener} for each launcher session when Tia
 * is enabled. The listener reads no version control system - the build JVM resolves the branch and
 * commit and hands them over - so one module serves Git and Perforce projects alike.
 */
public class TiaLauncherSessionListener implements LauncherSessionListener {

    /**
     * Shared across every session in the JVM. Load-bearing: {@code launcherSessionOpened} fires more
     * than once per JVM (Surefire re-runs open a new session), and the per-JVM run data must carry
     * across them.
     */
    private static final SharedTestRunData sharedTestRunData = new SharedTestRunData();

    /**
     * Invoked by JUnit Platform when a new launcher session opens. Registers a
     * {@link TiaTestExecutionListener} for the session, but only when Tia is enabled for this run
     * (system property {@code tiaEnabled=true}). When Tia is disabled or unset the method is a no-op,
     * so the shipped service descriptor does not interfere with non-Tia test runs that happen to
     * have the jar on the classpath.
     *
     * @param session the JUnit Platform launcher session that has just opened
     */
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (Boolean.parseBoolean(System.getProperty("tiaEnabled"))) {
            session.getLauncher().registerTestExecutionListeners(new TiaTestExecutionListener(sharedTestRunData));
        }
    }
}
