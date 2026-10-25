package com.gitstore.exception;

/**
 * Thrown when access to the repository is forbidden or the repository does not exist.
 * E.g., token lacks 'Contents: Read and write' scope, or repo path is wrong.
 */
public class RepositoryAccessException extends GitStoreException {
    public RepositoryAccessException(String message) {
        super(message);
    }

    public RepositoryAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
