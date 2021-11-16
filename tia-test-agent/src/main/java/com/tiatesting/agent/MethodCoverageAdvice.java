package com.tiatesting.agent;

import net.bytebuddy.asm.Advice;

public class MethodCoverageAdvice {

    @Advice.OnMethodEnter
    static long invokeBeforeEnterMethod(
            @Advice.Origin String method) {
        System.out.println("Method invoked before enter method by: " + method);
        MethodCoverage.getReferencedClasses().add(method);
        return System.currentTimeMillis();
    }
/*
    @Advice.OnMethodExit
    static void invokeAfterExitMethod(@Advice.Origin String method, @Advice.Enter long startTime) {
    }
    */
}
