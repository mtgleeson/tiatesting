package org.tiatesting.junit.junit5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherSession;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TiaLauncherSessionListener}: the {@code tiaEnabled} system-property gate, and
 * that an enabled session registers the Tia listener without any version control system. The
 * listener ships in tia-junit5's META-INF/services descriptor, so it must be a no-op for any test
 * run that has the jar on the classpath without Tia being explicitly enabled - otherwise IDE runs
 * (or any run that doesn't activate the Tia profile) would trigger Tia bootstrapping.
 */
class TiaLauncherSessionListenerTest {

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "tiaProjectDir"
    };

    @TempDir
    File workspaceWithNoRepository;

    private Map<String, String> savedProperties;

    /**
     * Save and clear every system property these tests touch.
     */
    @BeforeEach
    void setUp() {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    /**
     * Restore the system properties saved in {@link #setUp()}.
     */
    @AfterEach
    void tearDown() {
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * When {@code tiaEnabled} is not set in system properties the listener must
     * not touch the {@link LauncherSession}. We verify that by asserting
     * {@link LauncherSession#getLauncher()} is never called.
     */
    @Test
    void launcherSessionOpened_doesNothing_whenTiaEnabledUnset() {
        // given
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

    /**
     * An enabled session registers a {@link TiaTestExecutionListener} in a workspace with no
     * repository, given the branch and commit the build JVM resolved - the state every fork runs
     * in, since the test JVM never reads the VCS itself.
     */
    @Test
    void launcherSessionOpened_registersTiaListener_withoutARepository_whenTiaEnabled() {
        // given
        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "false");
        System.setProperty("tiaUpdateDBTestRunHistory", "false");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");
        System.setProperty("tiaProjectDir", workspaceWithNoRepository.getAbsolutePath());
        LauncherSession session = mock(LauncherSession.class);
        Launcher launcher = mock(Launcher.class);
        when(session.getLauncher()).thenReturn(launcher);
        TiaLauncherSessionListener listener = new TiaLauncherSessionListener();

        // when
        listener.launcherSessionOpened(session);

        // then
        verify(launcher).registerTestExecutionListeners(isA(TiaTestExecutionListener.class));
    }
}
