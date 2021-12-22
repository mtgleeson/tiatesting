package org.tiatesting.junit.junit4;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tiatesting.vcs.git.GitReader;

public class TiaTestingJunit4PerforceListener extends TiaTestingJunit4Listener {

    private static final Log log = LogFactory.getLog(TiaTestingJunit4PerforceListener.class);

    public TiaTestingJunit4PerforceListener() {
       super(new GitReader(System.getProperty("tiaProjectDir"))); // TODO
    }

}
