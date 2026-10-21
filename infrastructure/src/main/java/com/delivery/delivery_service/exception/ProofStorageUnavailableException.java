package com.delivery.delivery_service.exception;

/** A required private object-storage capability has not been configured. */
public class ProofStorageUnavailableException extends RuntimeException {
    public ProofStorageUnavailableException(String message) {
        super(message);
    }
}
