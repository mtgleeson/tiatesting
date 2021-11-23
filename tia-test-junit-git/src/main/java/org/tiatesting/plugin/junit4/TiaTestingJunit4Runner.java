package org.tiatesting.plugin.junit4;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;

public class TiaTestingJunit4Runner extends BlockJUnit4ClassRunner {

    protected static TiaTestingJunit4Listener listener = null;

    private static Log log = LogFactory.getLog(TiaTestingJunit4Runner.class);

    static {
        try {
            listener = new TiaTestingJunit4Listener();
        } catch (Exception e) {
            log.error("Error launching the Runner ", e);
        }
    }

    public TiaTestingJunit4Runner(Class<?> klass) throws InitializationError {
        super(klass);
    }

    protected void setUpListener(RunNotifier notifier) {
        if (getListener() != null) {
            notifier.addListener(getListener());
            removeListener();
        }
    }

    protected TiaTestingJunit4Listener getListener() {
        return listener;
    }

    protected void removeListener() {
        listener = null;
    }

    @Override
    public void run(RunNotifier notifier) {
        setUpListener(notifier);
        notifier.fireTestRunStarted(getDescription());
        super.run(notifier);
    }

    @Override
    protected boolean isIgnored(FrameworkMethod child) {
        System.out.println("Checking if method is ignored!! " + child.getName());
        if(shouldIgnore()) {
            return true;
        }
        return super.isIgnored(child);
    }

    private boolean shouldIgnore() {
        return false;
    }
}