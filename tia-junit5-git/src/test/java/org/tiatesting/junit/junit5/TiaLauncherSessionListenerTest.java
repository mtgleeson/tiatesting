package org.tiatesting.junit.junit5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherSession;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link TiaLauncherSessionListener} verifying the {@code tiaEnabled}
 * system-property gate. The listener ships in tia-junit5-git's META-INF/services
 * descriptor, so it must be a no-op for any test run that has the jar on the
 * classpath without Tia being explicitly enabled - otherwise IDE runs (or any
 * run that doesn't activate the Tia profile) would trigger Tia bootstrapping.
 */
class TiaLauncherSessionListenerTest {

    @AfterEach
    void clearTiaEnabled() {
        System.clearProperty("tiaEnabled");
    }

    /**
     * When {@code tiaEnabled} is not set in system properties the listener must
     * not touch the {@link LauncherSession}. We verify that by asserting
     * {@link LauncherSession#getLauncher()} is never called.
     */
    @Test
    void launcherSessionOpened_doesNothing_whenTiaEnabledUnset() {
        // given
        System.clearProperty("tiaEnabled");
        LauncherSession session = mock(LauncherSession.class);
        TiaLauncherSessionListener listener = new TiaLauncherSessionListener();

        // when
        listener.launcherSessionOpened(session);

        // then
        verify(session, never()).getLauncher();
    }

    /**
     * When {@code tiaEnabled} is set to "false" the listener must not touch the
     * {@link LauncherSession}. Same shape as the unset case but exercises the
     * {@code Boolean.parseBoolean("false")} branch explicitly.
     */
    @Test
    void launcherSessionOpened_doesNothing_whenTiaEnabledFalse() {
        // given
        System.setProperty("tiaEnabled", "false");
        LauncherSession session = mock(LauncherSession.class);
        TiaLauncherSessionListener listener = new TiaLauncherSessionListener();

        // when
        listener.launcherSessionOpened(session);

        // then
        verify(session, never()).getLauncher();
    }
}
