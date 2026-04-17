package com.swiftpay.ledger.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka event consumed from the "payment-initiated" topic.
 * Published by the Transaction Gateway.
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
