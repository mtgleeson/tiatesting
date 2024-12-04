package org.tiatesting.junit.junit4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.vcs.perforce.P4Reader;

public class TiaJunit4PerforceListener extends TiaJunit4Listener {

    private static final Logger log = LoggerFactory.getLogger(TiaJunit4PerforceListener.class);

    public TiaJunit4PerforceListener() {
       super(new P4Reader(Boolean.parseBoolean(System.getProperty("tiaEnabled")), System.getProperty("tiaVcsServerUri"),
               System.getProperty("tiaVcsUserName"), System.getProperty("tiaVcsPassword"),
               System.getProperty("tiaVcsClientName")));
    }

}
