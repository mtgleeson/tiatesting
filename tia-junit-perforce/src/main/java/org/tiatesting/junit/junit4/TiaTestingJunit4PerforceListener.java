package org.tiatesting.junit.junit4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.vcs.perforce.P4Reader;

public class TiaTestingJunit4PerforceListener extends TiaTestingJunit4Listener {

    private static final Logger log = LoggerFactory.getLogger(TiaTestingJunit4PerforceListener.class);

    public TiaTestingJunit4PerforceListener() {
       super(new P4Reader(Boolean.valueOf(System.getProperty("tiaEnabled")), System.getProperty("tiaVcsServerUri"),
               System.getProperty("tiaVcsUserName"), System.getProperty("tiaVcsPassword"),
               System.getProperty("tiaVcsClientName")));
    }

}
