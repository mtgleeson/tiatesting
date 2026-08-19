package org.tiatesting.vcs.perforce.connection;

public class P4Constants
{
    // P4 default settings
    public static final String P4_DEFAULT_SERVER_URI_PROPERTY_KEY = "p4.default.server.uri";

    // P4 commands. The argument vector is given explicitly rather than as a string to be split on
    // spaces: splitting breaks the moment the executable is an absolute path containing a space
    // (the default install location on Windows is under "Program Files"), and passing the argv
    // straight to ProcessBuilder also avoids going through a shell, which is what made the previous
    // "cmd.exe /c" form Windows-only. P4_SET is the human-readable rendering, used in messages.
    public static final String P4_EXECUTABLE = "p4";
    public static final String P4_SET_ARG = "set";
    public static final String P4_SET = P4_EXECUTABLE + " " + P4_SET_ARG;

    // P4 env variables
    public static final String P4PORT = "P4PORT";
    public static final String P4USER = "P4USER";
    public static final String P4CLIENT = "P4CLIENT";
    public static final String P4PASSWD = "P4PASSWD";
}
