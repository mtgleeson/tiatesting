package org.tiatesting.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Ignore;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

public class Agent {

    private static Log log = LogFactory.getLog(Agent.class);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        Set<String> ignoredTests = new HashSet<>();
        ignoredTests.add("com.ea.fos.junit.utasft.scenario.user.PreOrderPackTest");
       // ignoredTests.add("com.example.DoorServiceTest");

        log.info("Ignoring tests: " + ignoredTests);

        AnnotationDescription ignoreDescription = AnnotationDescription.Builder.ofType(Ignore.class)
                .define("value", "Ignored by TIA testing")
                .build();

        new AgentBuilder.Default()
                .type(ElementMatchers.namedOneOf(ignoredTests.toArray(new String[ignoredTests.size()])))
                .transform((builder, typeDescription, agr3, arg4) -> builder.annotateType(ignoreDescription))
                .installOn(instrumentation);
    }
}