package com.gitstore.exception;

/**
 * Thrown when a Git push fails due to a non-fast-forward conflict or concurrent write race.
 */
public class PushConflictException extends GitStoreException {
    public PushConflictException(String message) {
        super(message);
    }

    public PushConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
