package org.tiatesting.spock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spockframework.runtime.model.ErrorInfo;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.MethodInfo;
import org.spockframework.runtime.model.MethodKind;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.testrunner.RunAttempt;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers which spec {@link TiaSpockRunListener#error} records as failed. Spock hands the listener
 * the failing {@link MethodInfo}, and only a feature method has a feature - fixtures and data
 * providers do not - so the spec has to come from the method's parent. That parent is the spec
 * that declares the method, which for anything inherited is a base class, so the listener must
 * record the bottom spec that was actually running.
 *
 * <p>The spec model is built by hand, the same shape Spock's {@code SpecInfoBuilder} produces: a
 * base spec and a subclass linked through their super/sub references, with each method parented to
 * the spec that declares it. The listener runs with mapping and history off and no data store,
 * since what is under test is the classification, not the persist.
 */
class TiaSpockRunListenerErrorTest {

    private static final String PACKAGE = "com.example";
    private static final String SPEC = PACKAGE + ".ASpec";

    private SpecInfo baseSpec;
    private SpecInfo spec;
    private TiaSpockRunListener listener;

    /**
     * Build a base spec with a subclass, and a listener that has seen the subclass start.
     */
    @BeforeEach
    void setUp() {
        baseSpec = specInfo("BaseSpec");
        spec = specInfo("ASpec");
        spec.setSuperSpec(baseSpec);
        baseSpec.setSubSpec(spec);

        listener = new TiaSpockRunListener("main", "commit-1", null, Collections.singleton(SPEC), 0,
                false, false, null, TestRunSelectionDetails.empty(), null, RunAttempt.FIRST);
        listener.beforeSpec(spec);
    }

    /**
     * Verifies an error in a {@code setup} fixture - a method with no feature - records the spec as
     * failed instead of throwing.
     */
    @Test
    void error_inSetupFixture_failsTheSpec() {
        // given
        MethodInfo setup = method(spec, "setup", MethodKind.SETUP);

        // when
        listener.error(new ErrorInfo(setup, new IllegalStateException("setup")));

        // then
        assertEquals(Collections.singleton(SPEC), listener.getTestSuitesFailed());
    }

    /**
     * Verifies an error in a {@code where:} data provider - also a method with no feature - records
     * the spec as failed instead of throwing.
     */
    @Test
    void error_inDataProvider_failsTheSpec() {
        // given
        MethodInfo provider = method(spec, "$spock_feature_0_0prov0", MethodKind.DATA_PROVIDER);

        // when
        listener.error(new ErrorInfo(provider, new IllegalStateException("provider")));

        // then
        assertEquals(Collections.singleton(SPEC), listener.getTestSuitesFailed());
    }

    /**
     * Verifies a failing feature inherited from a base spec is recorded against the subclass that
     * ran it, not the base class that declares it.
     */
    @Test
    void error_inInheritedFeature_failsTheRunningSpec() {
        // given
        MethodInfo inherited = featureMethod(baseSpec, "inherited feature");

        // when
        listener.error(new ErrorInfo(inherited, new AssertionError("boom")));

        // then
        assertEquals(Collections.singleton(SPEC), listener.getTestSuitesFailed());
    }

    /**
     * Verifies an inherited {@code setupSpec} fixture that fails is recorded against the running
     * subclass.
     */
    @Test
    void error_inInheritedSetupSpec_failsTheRunningSpec() {
        // given
        MethodInfo setupSpec = method(baseSpec, "setupSpec", MethodKind.SETUP_SPEC);

        // when
        listener.error(new ErrorInfo(setupSpec, new IllegalStateException("setupSpec")));

        // then
        assertEquals(Collections.singleton(SPEC), listener.getTestSuitesFailed());
    }

    /**
     * Verifies a failing feature declared on the running spec itself is still recorded against it.
     */
    @Test
    void error_inOwnFeature_failsTheSpec() {
        // given
        MethodInfo own = featureMethod(spec, "own feature");

        // when
        listener.error(new ErrorInfo(own, new AssertionError("boom")));

        // then
        assertEquals(Collections.singleton(SPEC), listener.getTestSuitesFailed());
    }

    /**
     * Build a spec in {@link #PACKAGE}.
     *
     * @param name the spec's simple name
     * @return the spec
     */
    private static SpecInfo specInfo(final String name) {
        SpecInfo specInfo = new SpecInfo();
        specInfo.setPackage(PACKAGE);
        specInfo.setName(name);
        return specInfo;
    }

    /**
     * Build a non-feature method declared by a spec, as {@code SpecInfoBuilder} does for fixtures
     * and data providers: parented to the spec, with no feature set.
     *
     * @param declaringSpec the spec that declares the method
     * @param name the method name
     * @param kind the method kind
     * @return the method
     */
    private static MethodInfo method(final SpecInfo declaringSpec, final String name, final MethodKind kind) {
        MethodInfo method = new MethodInfo();
        method.setParent(declaringSpec);
        method.setName(name);
        method.setKind(kind);
        return method;
    }

    /**
     * Build a feature method declared by a spec, with its feature parented to the same spec.
     *
     * @param declaringSpec the spec that declares the feature
     * @param name the feature name
     * @return the feature method
     */
    private static MethodInfo featureMethod(final SpecInfo declaringSpec, final String name) {
        FeatureInfo feature = new FeatureInfo();
        feature.setParent(declaringSpec);
        feature.setName(name);
        MethodInfo method = method(declaringSpec, name, MethodKind.FEATURE);
        method.setFeature(feature);
        feature.setFeatureMethod(method);
        return method;
    }
}
