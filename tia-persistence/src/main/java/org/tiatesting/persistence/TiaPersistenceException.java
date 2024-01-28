package org.tiatesting.persistence;

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

}
