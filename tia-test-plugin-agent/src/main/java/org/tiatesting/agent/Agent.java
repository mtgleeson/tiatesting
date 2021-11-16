package org.tiatesting.agent;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.lang.instrument.Instrumentation;

public class Agent {

    private static Log log = LogFactory.getLog(Agent.class);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("YOOOOOOOOOOOO!!!!");
    }
}