package org.tiatesting.junit.junit5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.vcs.perforce.P4Reader;

public class TiaJunit5PerforceListener extends TiaTestExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(TiaJunit5PerforceListener.class);

    public TiaJunit5PerforceListener(final SharedTestRunData sharedTestRunData) {
        super(sharedTestRunData, new P4Reader(Boolean.parseBoolean(System.getProperty("tiaEnabled")),
                System.getProperty("tiaVcsServerUri"), System.getProperty("tiaVcsUserName"),
                System.getProperty("tiaVcsPassword"), System.getProperty("tiaVcsClientName")));
    }
}
