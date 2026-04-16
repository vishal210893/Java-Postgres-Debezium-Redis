package com.example.cdc.exception;

/**
 * <b>Entity Not Found Exception</b>
 *
 * <p>Thrown when a requested entity does not exist in PostgreSQL (write operations)
 * or Redis (read operations).
 */
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String entityType, Long id) {
        super("%s with id %d not found".formatted(entityType, id));
    }

    public EntityNotFoundException(String message) {
        super(message);
    }
}
