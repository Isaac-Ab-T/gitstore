package com.gitstore.exception;

/**
 * Thrown when GitHub authentication fails (e.g. invalid, expired, or missing personal access token).
 */
public class AuthenticationException extends GitStoreException {
    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
