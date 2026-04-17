package com.swiftpay.gateway.exception;

/**
 * Thrown when a duplicate idempotency key is detected in Redis within the TTL window.
 */
public class DuplicatePaymentException extends RuntimeException {

    private final String idempotencyKey;

    public DuplicatePaymentException(String idempotencyKey) {
        super("Duplicate payment request for idempotency key: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
