package org.tiatesting.core.persistence;

/**
 * Represents any issue when dealing with the persistence layer of Tia.
 * TiaPersistenceException is a runtime exception as callers aren't expected to deal with these exceptions
 * other than pass them up the stack.
 */
public class TiaPersistenceException extends RuntimeException {

    public TiaPersistenceException(String message){
        super(message);
    }

    public TiaPersistenceException(Exception exception){
        super(exception);
    }

    /**
     * Wrap a lower-level failure while preserving both an explanatory Tia message and the original
     * cause, so callers see the Tia guidance and can still inspect the underlying exception.
     *
     * @param message the explanatory Tia message
     * @param cause   the original underlying exception
     */
    public TiaPersistenceException(String message, Throwable cause){
        super(message, cause);
    }

}
