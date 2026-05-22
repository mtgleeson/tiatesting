package org.tiatesting.junit.junit5;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TiaLauncherSessionListener implements LauncherSessionListener {
    private static final Logger log = LoggerFactory.getLogger(TiaLauncherSessionListener.class);

    private static final SharedTestRunData sharedTestRunData = new SharedTestRunData();

    /**
     * Invoked by JUnit Platform when a new launcher session opens. Registers the
     * Tia Perforce {@link TiaJunit5PerforceListener} as a {@link org.junit.platform.launcher.TestExecutionListener}
     * for the session, but only when Tia is enabled for this run (system property
     * {@code tiaEnabled=true}). When Tia is disabled or unset the method is a no-op,
     * so the shipped service descriptor inside the tia-junit5-perforce jar does not
     * interfere with non-Tia test runs that happen to have the jar on the classpath.
     *
     * @param session the JUnit Platform launcher session that has just opened
     */
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (Boolean.parseBoolean(System.getProperty("tiaEnabled"))) {
            session.getLauncher().registerTestExecutionListeners(new TiaJunit5PerforceListener(sharedTestRunData));
        }
    }
}
