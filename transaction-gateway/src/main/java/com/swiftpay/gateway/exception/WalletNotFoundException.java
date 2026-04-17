package com.swiftpay.gateway.exception;

/**
 * Thrown when a referenced wallet/user is not found.
 */
public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(String userId) {
        super("Wallet not found for user: " + userId);
    }
}
