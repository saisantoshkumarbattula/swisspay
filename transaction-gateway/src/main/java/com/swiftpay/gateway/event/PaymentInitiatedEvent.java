package com.swiftpay.gateway.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted to Kafka when a payment is initiated.
 * Consumed by the Ledger Service for atomic debit/credit processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitiatedEvent {

    private UUID transactionId;
    private String idempotencyKey;
    private UUID senderId;
    private UUID receiverId;
    private BigDecimal amount;
    private String currency;
    private Instant initiatedAt;
}
