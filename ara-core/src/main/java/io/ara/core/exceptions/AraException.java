package io.ara.core.exceptions;

/**
 * Base exception for all ARA framework exceptions
 */
public class AraException extends RuntimeException {
    
    private static final long serialVersionUID = 3178851156633560286L;

	public AraException(String message) {
        super(message);
    }
    
    public AraException(String message, Throwable cause) {
        super(message, cause);
    }
}