package com.gitstore.exception;

public class GitStoreException extends RuntimeException {
    public GitStoreException(String message) {
        super(message);
    }

    public GitStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
