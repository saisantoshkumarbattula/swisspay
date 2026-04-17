package com.swiftpay.gateway.domain;

/**
 * Lifecycle states of a payment transaction.
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
