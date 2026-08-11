package io.ara.core.exceptions;

/**
 * Base exception for all ARA framework exceptions
 */
public class AraException extends RuntimeException {
    
    public AraException(String message) {
        super(message);
    }
    
    public AraException(String message, Throwable cause) {
        super(message, cause);
    }
}