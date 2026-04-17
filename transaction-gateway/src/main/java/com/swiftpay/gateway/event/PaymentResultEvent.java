package com.swiftpay.gateway.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event received from Kafka when the Ledger Service
 * completes (or fails) the payment processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResultEvent {

    private UUID transactionId;
    private String status;   // "COMPLETED" or "FAILED"
    private String failureReason;
    private BigDecimal amount;
    private String currency;
    private UUID senderId;
    private UUID receiverId;
    private Instant processedAt;
}
