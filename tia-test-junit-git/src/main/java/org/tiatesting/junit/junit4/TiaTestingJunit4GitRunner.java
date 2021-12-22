package org.tiatesting.junit.junit4;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.runners.model.InitializationError;

public class TiaTestingJunit4GitRunner extends TiaTestingJunit4Runner {

    private static Log log = LogFactory.getLog(TiaTestingJunit4GitRunner.class);

    static {
        try {
            listener = new TiaTestingJunit4GitListener();
        } catch (Exception e) {
            log.error("Error launching the Runner ", e);
        }
    }

    public TiaTestingJunit4GitRunner(Class<?> klass) throws InitializationError {
        super(klass);
    }
}