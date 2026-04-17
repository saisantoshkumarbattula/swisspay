package com.swiftpay.gateway.exception;

/**
 * Thrown when a sender's wallet has insufficient balance to complete a transfer.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
