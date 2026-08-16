package org.tiatesting.spock;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers {@link TiaSpockSkipExecutionListener} with the JUnit Platform launcher session Gradle
 * opens for a {@code Test} task, mirroring how {@code tia-junit5-git}'s {@code
 * TiaLauncherSessionListener} registers its own execution listener for Maven Surefire. Discovered
 * via the {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener} entry this
 * class ships in {@code tia-spock}'s jar; a {@code LauncherSessionListener} is looked up by {@code
 * ServiceLoader} independently of the {@code TestExecutionListener} auto-registration flag some
 * runners disable, and calling {@code session.getLauncher().registerTestExecutionListeners(...)}
 * here registers the listener directly rather than relying on that flag - Gradle's own JUnit
 * Platform test worker (confirmed against {@code gradle-testing-junit-platform}'s bytecode) opens
 * this same kind of session via {@code LauncherFactory.openSession()} for every {@code Test} task,
 * so this fires there exactly as it does under Maven Surefire.
 *
 * <p>Registration happens {@code start()}-before-{@code visitSpec()}-before-any-test, which is
 * before Spock's own {@link TiaSpockGlobalExtension} is constructed, so {@link
 * TiaSpockSkipExecutionListener} writes into {@link SharedSpockSkipObservation} - a JVM-static
 * bridge - rather than into an instance the extension could hand it directly.
 */
public class TiaSpockLauncherSessionListener implements LauncherSessionListener {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockLauncherSessionListener.class);

    /**
     * Register {@link TiaSpockSkipExecutionListener} for the session that just opened, but only
     * when Tia is enabled for this run (system property {@code tiaEnabled=true}). When Tia is
     * disabled or unset this is a no-op, so the shipped service descriptor inside the {@code
     * tia-spock} jar does not interfere with a non-Tia test run that happens to have the jar on its
     * classpath.
     *
     * @param session the JUnit Platform launcher session that has just opened
     */
    @Override
    public void launcherSessionOpened(final LauncherSession session) {
        if (Boolean.parseBoolean(System.getProperty("tiaEnabled"))) {
            log.debug("Tia is enabled: registering the Spock skip-observation TestExecutionListener "
                    + "with the JUnit Platform launcher session.");
            session.getLauncher().registerTestExecutionListeners(new TiaSpockSkipExecutionListener());
        }
    }
}
