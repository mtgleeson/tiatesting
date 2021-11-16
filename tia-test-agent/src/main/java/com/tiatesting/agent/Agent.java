package com.tiatesting.agent;

import com.tiatesting.agent.server.TestRunnerServer;
import fi.iki.elonen.NanoHTTPD;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.lang.instrument.Instrumentation;

public class Agent {

    private static Log log = LogFactory.getLog(Agent.class);

    private static void configureInstrumentation(Instrumentation instrumentation, String packagesToInstrument){

        if (packagesToInstrument == null || packagesToInstrument.trim().equals("")){
            log.error("Please configure the packages name of the source code to collect test mapping for.");
            throw new IllegalArgumentException("Please configure the packages name of the source code to collect test mapping for.");
        }

        new AgentBuilder.Default()
                .type(ElementMatchers.nameContainsIgnoreCase(packagesToInstrument))
                .transform((builder, typeDescription, classLoader, module) -> builder
                        .method(ElementMatchers.any())
                        .intercept(Advice.to(MethodCoverageAdvice.class)))
                .installOn(instrumentation);

        log.info("Instrumentation has been configured for the packages: " + packagesToInstrument);
    }

    private static void startTestRunnerServer() throws IOException {
        TestRunnerServer server = new TestRunnerServer();
        // set daemon to true so it doesn't prevent the application from shutting down due a user thread being still open for the server
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        log.info("The test runner server has started and is ready to receive notifications from the test runner.");
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) throws IOException {
        startTestRunnerServer();
        configureInstrumentation(instrumentation, "com.example");
    }
}