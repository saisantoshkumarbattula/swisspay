package com.swiftpay.ledger.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka event published to "payment-completed" or "payment-failed" topics.
 * Consumed by the Transaction Gateway to update status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResultEvent {
    private UUID transactionId;
    private String status;           // "COMPLETED" or "FAILED"
    private String failureReason;
    private BigDecimal amount;
    private String currency;
    private UUID senderId;
    private UUID receiverId;
    private Instant processedAt;
}
