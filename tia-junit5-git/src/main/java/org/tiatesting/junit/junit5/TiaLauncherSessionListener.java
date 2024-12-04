package org.tiatesting.junit.junit5;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TiaLauncherSessionListener implements LauncherSessionListener {
    private static final Logger log = LoggerFactory.getLogger(TiaLauncherSessionListener.class);

    private static final SharedTestRunData sharedTestRunData = new SharedTestRunData();

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        session.getLauncher().registerTestExecutionListeners(new TiaJunit5GitListener(sharedTestRunData));
    }
}
