package org.tiatesting.spock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryListener;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TiaSpockLauncherSessionListener} verifying the {@code tiaEnabled} system-property
 * gate. The listener ships in {@code tia-spock}'s {@code META-INF/services} descriptor, so it must
 * be a no-op for any test run that has the jar on the classpath without Tia being explicitly
 * enabled - otherwise an IDE run, or any Gradle build that doesn't set {@code tiaEnabled}, would
 * register the skip-observation listener for no reason.
 */
class TiaSpockLauncherSessionListenerTest {

    /**
     * Clear the {@code tiaEnabled} gate between tests, since it is a JVM-wide system property and a
     * value left set would decide the next test's outcome before it ran.
     */
    @AfterEach
    void clearTiaEnabled() {
        System.clearProperty("tiaEnabled");
    }

    /**
     * The registration descriptor must actually name this class. Nothing else fails loudly if it
     * does not: a typo in {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}
     * or in the class's fully-qualified name leaves the skip listener silently unregistered, and a
     * Gradle/Spock runner then observes no skipped spec at all - so the distributed completeness
     * guard reads short of the group's assigned count forever, the group never completes, and the
     * build never seals. This asserts the descriptor rather than the listener's behaviour, because
     * the descriptor is the part no other test in this class can reach.
     */
    @Test
    void theListenerIsDiscoverableThroughTheServiceLoader() {
        // given
        boolean found = false;

        // when
        for (LauncherSessionListener candidate : ServiceLoader.load(LauncherSessionListener.class,
                TiaSpockLauncherSessionListenerTest.class.getClassLoader())) {
            if (candidate instanceof TiaSpockLauncherSessionListener) {
                found = true;
            }
        }

        // then
        assertTrue(found, "TiaSpockLauncherSessionListener must be discoverable through the "
                + "META-INF/services descriptor, or a Gradle/Spock runner never registers the skip "
                + "observation the distributed completeness guard depends on");
    }

    /**
     * When {@code tiaEnabled} is not set the listener must not touch the session's launcher at all.
     */
    @Test
    void launcherSessionOpenedDoesNothingWhenTiaEnabledUnset() {
        // given
        System.clearProperty("tiaEnabled");
        RecordingLauncher launcher = new RecordingLauncher();
        TiaSpockLauncherSessionListener listener = new TiaSpockLauncherSessionListener();

        // when
        listener.launcherSessionOpened(new StubLauncherSession(launcher));

        // then
        assertTrue(launcher.registered.isEmpty(),
                "the launcher must not be touched when tiaEnabled is unset");
    }

    /**
     * When {@code tiaEnabled} is set to {@code false} the listener must likewise register nothing.
     */
    @Test
    void launcherSessionOpenedDoesNothingWhenTiaEnabledFalse() {
        // given
        System.setProperty("tiaEnabled", "false");
        RecordingLauncher launcher = new RecordingLauncher();
        TiaSpockLauncherSessionListener listener = new TiaSpockLauncherSessionListener();

        // when
        listener.launcherSessionOpened(new StubLauncherSession(launcher));

        // then
        assertTrue(launcher.registered.isEmpty(),
                "the launcher must not be touched when tiaEnabled is false");
    }

    /**
     * When {@code tiaEnabled} is {@code true} the listener must register exactly one {@link
     * TiaSpockSkipExecutionListener} with the session's launcher.
     */
    @Test
    void launcherSessionOpenedRegistersTheSkipListenerWhenTiaEnabled() {
        // given
        System.setProperty("tiaEnabled", "true");
        RecordingLauncher launcher = new RecordingLauncher();
        TiaSpockLauncherSessionListener listener = new TiaSpockLauncherSessionListener();

        // when
        listener.launcherSessionOpened(new StubLauncherSession(launcher));

        // then
        assertEquals(1, launcher.registered.size(),
                "exactly one execution listener must be registered");
        assertTrue(launcher.registered.get(0) instanceof TiaSpockSkipExecutionListener,
                "the registered listener must be the skip-observation listener");
    }

    /**
     * A {@link LauncherSession} stub that always hands back the given {@link Launcher}, since
     * nothing under test ever closes the session.
     */
    private static final class StubLauncherSession implements LauncherSession {

        private final Launcher launcher;

        private StubLauncherSession(final Launcher launcher) {
            this.launcher = launcher;
        }

        @Override
        public Launcher getLauncher() {
            return launcher;
        }

        @Override
        public void close() {
        }
    }

    /**
     * A {@link Launcher} stub that records every {@link TestExecutionListener} registered with it,
     * so a test can assert on what the listener under test did without a mocking framework.
     */
    private static final class RecordingLauncher implements Launcher {

        private final List<TestExecutionListener> registered = new ArrayList<>();

        @Override
        public void registerLauncherDiscoveryListeners(final LauncherDiscoveryListener... listeners) {
        }

        @Override
        public void registerTestExecutionListeners(final TestExecutionListener... listeners) {
            for (TestExecutionListener listener : listeners) {
                registered.add(listener);
            }
        }

        @Override
        public TestPlan discover(final LauncherDiscoveryRequest launcherDiscoveryRequest) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void execute(final LauncherDiscoveryRequest launcherDiscoveryRequest,
                            final TestExecutionListener... listeners) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void execute(final TestPlan testPlan, final TestExecutionListener... listeners) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
