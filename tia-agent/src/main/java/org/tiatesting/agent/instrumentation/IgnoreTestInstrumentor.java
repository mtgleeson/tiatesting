package org.tiatesting.agent.instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.annotation.Annotation;
import java.lang.instrument.Instrumentation;
import java.util.Set;

public class IgnoreTestInstrumentor {

    /**
     * Instrument the code to add the junit @Ignore to a given set of test classes.
     *
     * @param ignoredTests
     * @param instrumentation
     * @param ignoreClass
     */
    public void ignoreTests(final Set<String> ignoredTests, Instrumentation instrumentation, Class<? extends Annotation> ignoreClass){
        AnnotationDescription ignoreDescription = AnnotationDescription.Builder.ofType(ignoreClass)
                .define("value", "Ignored by TIA testing")
                .build();

        new AgentBuilder.Default()
                .type(ElementMatchers.namedOneOf(ignoredTests.toArray(new String[ignoredTests.size()])))
                .transform((builder, typeDescription, agr3, arg4) -> builder.annotateType(ignoreDescription))
                .installOn(instrumentation);
    }
}
