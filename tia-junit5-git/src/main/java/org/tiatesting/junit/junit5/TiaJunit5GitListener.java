package org.tiatesting.junit.junit5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.vcs.git.GitReader;

public class TiaJunit5GitListener extends TiaTestExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(TiaJunit5GitListener.class);

    public TiaJunit5GitListener(final SharedTestRunData sharedTestRunData) {
        super(sharedTestRunData, new GitReader(System.getProperty("tiaProjectDir")));
    }
}
