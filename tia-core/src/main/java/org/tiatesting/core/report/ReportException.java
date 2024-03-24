package org.tiatesting.core.report;

/**
 * Represents any issue when generating reports.
 * ReportException is a runtime exception as callers aren't expected to deal with these exceptions.
 */
public class ReportException extends RuntimeException {

    public ReportException(String message){
        super(message);
    }

    public ReportException(Exception exception){
        super(exception);
    }

}

