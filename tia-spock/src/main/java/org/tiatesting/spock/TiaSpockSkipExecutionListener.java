package org.tiatesting.spock;

import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Source Spock's skip observation from the JUnit Platform rather than from Spock's own {@code
 * IRunListener.specSkipped}, which the Spock 2.0 and 2.4-M1 engines never call - verified against
 * the compiled {@code spock-core} jars, where the only call sites for {@code specSkipped} are the
 * delegating wrappers ({@code MasterRunSupervisor}, {@code MasterRunListener}, {@code
 * AsyncRunListener}), none of which the engine itself invokes.
 *
 * <p>Spock 2.x runs its specs on the JUnit Platform, and {@code SpecNode.shouldBeSkipped(...)}
 * reports a spec skipped by {@code @Ignore}, {@code @IgnoreIf}, {@code @Requires} or an exclusion
 * filter to the platform as {@code executionSkipped} - the same hook {@code
 * TiaTestExecutionListener} already uses on the JUnit 5 side. Tia's own group-based deselection
 * (the Gradle {@code TiaSpockGlobalExtension} calling {@code spec.skip(...)} on a suite this JVM's
 * group was not assigned) surfaces through the identical hook, so this listener also inflates the
 * observed set with every foreign suite Tia disabled - which is exactly why the distributed
 * completeness guard's count is intersected against the group's own assigned suites downstream in
 * {@code TestRunnerService}, not read off this set directly.
 *
 * <p>Registered by {@link TiaSpockLauncherSessionListener} only when {@code tiaEnabled=true}, and
 * writes into {@link SharedSpockSkipObservation} rather than holding its own state, since {@link
 * TiaSpockRunListener} - built later, by Spock's own {@code IGlobalExtension} SPI - is what
 * actually needs the observation at persist time.
 */
public class TiaSpockSkipExecutionListener implements TestExecutionListener {

    /**
     * Record a class-level (spec) skip as observed by this JVM. Individual feature (method) skips
     * are deliberately not recorded here: a spec with one skipped feature among several that ran is
     * already recorded as observed via {@link TiaSpockRunListener#afterSpec}, and recording the
     * method-level skip too would add nothing - only a class-level skip (the whole spec never ran)
     * needs this hook to be seen as observed at all.
     *
     * @param testIdentifier the identifier of the test (or container) that was skipped
     * @param reason human-readable explanation of why the execution was skipped; not used here
     */
    @Override
    public void executionSkipped(final TestIdentifier testIdentifier, final String reason) {
        if (isSpecLevelSkip(testIdentifier)) {
            String specName = ((ClassSource) testIdentifier.getSource().get()).getClassName();
            SharedSpockSkipObservation.suitesObservedViaSkip().add(specName);
        }
    }

    /**
     * Decide whether a skipped test identifier names a whole spec (a JUnit Platform container
     * sourced from a class) rather than one feature method within a spec that otherwise ran.
     *
     * @param testIdentifier the identifier to classify
     * @return true when the identifier is a class-level container, matching how {@code
     *         TiaTestExecutionListener.isExecutionForTestSuite} classifies the JUnit 5 equivalent
     */
    private boolean isSpecLevelSkip(final TestIdentifier testIdentifier) {
        return testIdentifier.isContainer() && testIdentifier.getSource().isPresent()
                && testIdentifier.getSource().get() instanceof ClassSource;
    }
}
