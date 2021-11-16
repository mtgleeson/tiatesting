package com.tiatesting.agent.server;

import fi.iki.elonen.NanoHTTPD;

public class TestRunnerServer extends NanoHTTPD {

    private static int PORT = 8180;

    public TestRunnerServer(){
        this(PORT);
    }

    public TestRunnerServer(int port){
        super(port);
    }

}
