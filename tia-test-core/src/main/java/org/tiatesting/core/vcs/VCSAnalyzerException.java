package org.tiatesting.core.vcs;

/**
 * Represents any issue when analyzing the VCS systems for source code files impacted in a given commit range.
 * VcsAnalyzerException is a runtime exception as callers aren't expected to deal with these exceptions
 * other than pass them up the stack.
 */
public class VCSAnalyzerException extends RuntimeException {

    public VCSAnalyzerException(Exception exception){
        super(exception);
    }

}
