package org.tiatesting.junit.junit4;

import org.junit.runners.model.InitializationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TiaTestingJunit4PerforceRunner extends TiaTestingJunit4Runner {

    private static final Logger log = LoggerFactory.getLogger(TiaTestingJunit4PerforceRunner.class);

    static {
        try {
            listener = new TiaTestingJunit4PerforceListener();
        } catch (Exception e) {
            log.error("Error launching the Runner ", e);
        }
    }

    public TiaTestingJunit4PerforceRunner(Class<?> klass) throws InitializationError {
        super(klass);
    }
}