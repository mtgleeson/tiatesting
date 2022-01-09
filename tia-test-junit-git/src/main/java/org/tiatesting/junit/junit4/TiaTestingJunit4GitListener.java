package org.tiatesting.junit.junit4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.vcs.git.GitReader;

public class TiaTestingJunit4GitListener extends TiaTestingJunit4Listener {

    private static final Logger log = LoggerFactory.getLogger(TiaTestingJunit4GitListener.class);

    public TiaTestingJunit4GitListener() {
       super(new GitReader(System.getProperty("tiaProjectDir")));
    }

}
