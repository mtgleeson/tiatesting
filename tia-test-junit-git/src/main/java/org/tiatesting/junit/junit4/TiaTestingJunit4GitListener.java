package org.tiatesting.junit.junit4;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tiatesting.vcs.git.GitReader;

public class TiaTestingJunit4GitListener extends TiaTestingJunit4Listener {

    private static final Log log = LogFactory.getLog(TiaTestingJunit4GitListener.class);

    public TiaTestingJunit4GitListener() {
       super(new GitReader(System.getProperty("tiaProjectDir")));
    }

}
