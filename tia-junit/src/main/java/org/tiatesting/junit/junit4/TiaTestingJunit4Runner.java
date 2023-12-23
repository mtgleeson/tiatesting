package org.tiatesting.junit.junit4;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TiaTestingJunit4Runner extends BlockJUnit4ClassRunner {

    protected static TiaTestingJunit4Listener listener = null;

    private static final Logger log = LoggerFactory.getLogger(TiaTestingJunit4Runner.class);

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
        if(shouldIgnore()) {
            return true;
        }
        return super.isIgnored(child);
    }

    private boolean shouldIgnore() {
        return false;
    }
}