package org.tiatesting.junit.junit4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.vcs.git.GitReader;

public class TiaJunit4GitListener extends TiaJunit4Listener {

    private static final Logger log = LoggerFactory.getLogger(TiaJunit4GitListener.class);

    public TiaJunit4GitListener() {
       super(new GitReader(System.getProperty("tiaProjectDir")));
    }

}
