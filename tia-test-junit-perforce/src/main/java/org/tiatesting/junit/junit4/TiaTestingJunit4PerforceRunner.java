package org.tiatesting.junit.junit4;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.runners.model.InitializationError;

public class TiaTestingJunit4PerforceRunner extends TiaTestingJunit4Runner {

    private static Log log = LogFactory.getLog(TiaTestingJunit4PerforceRunner.class);

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