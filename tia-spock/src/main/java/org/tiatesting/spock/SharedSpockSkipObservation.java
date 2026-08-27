package org.tiatesting.spock;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bridge between the two independent registration mechanisms a Gradle/Spock test JVM's skip
 * signal travels through: {@link TiaSpockSkipExecutionListener}, a JUnit Platform {@code
 * TestExecutionListener} registered by {@link TiaSpockLauncherSessionListener} before Spock's own
 * {@link TiaSpockGlobalExtension} is even constructed, and {@link TiaSpockRunListener}, which Spock
 * constructs later and which needs that same skip information merged into its own {@code
 * suitesObserved} set before it persists. Neither side holds a reference to the other - Spock's
 * {@code IGlobalExtension} SPI and the JUnit Platform's {@code LauncherSessionListener} SPI are
 * unrelated discovery mechanisms - so a JVM-static set is what carries the observation across that
 * gap. Safe as a plain static: a Gradle test worker JVM runs exactly one test session in its
 * lifetime, so there is only ever one build's worth of state to carry.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for why the guard needs a suite's
 * skip to be observed at all, and {@link TiaSpockSkipExecutionListener} for why the observation has
 * to be sourced from the JUnit Platform rather than from Spock's own {@code specSkipped} hook.
 */
final class SharedSpockSkipObservation {

    private static final Set<String> SUITES_OBSERVED_VIA_SKIP = ConcurrentHashMap.newKeySet();

    /**
     * Not instantiable: every member is static, since the whole point of this class is one set
     * shared across a JVM rather than a value owned by any one instance.
     */
    private SharedSpockSkipObservation() {
    }

    /**
     * The suite names this JVM's JUnit Platform listener has observed as skipped, keyed by the
     * spec's fully-qualified class name in {@code package.SpecName} form - the same format {@link
     * SpecificationUtil#getSpecName} produces, so a name added here matches one added to {@link
     * TiaSpockRunListener}'s own observed set without translation.
     *
     * @return the live, mutable, thread-safe set of spec names observed as skipped this JVM
     */
    static Set<String> suitesObservedViaSkip() {
        return SUITES_OBSERVED_VIA_SKIP;
    }
}
