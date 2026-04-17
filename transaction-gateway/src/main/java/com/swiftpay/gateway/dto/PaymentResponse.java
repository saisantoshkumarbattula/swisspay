package com.swiftpay.gateway.dto;

import com.swiftpay.gateway.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body returned after payment initiation.
 */
@Data
@Builder
@Schema(description = "Payment initiation response")
public class PaymentResponse {

    @Schema(description = "Unique payment transaction ID")
    private UUID transactionId;

    @Schema(description = "Idempotency key echoed back")
    private String idempotencyKey;

    @Schema(description = "Sender wallet ID")
    private UUID senderId;

    @Schema(description = "Receiver wallet ID")
    private UUID receiverId;

    @Schema(description = "Transfer amount")
    private BigDecimal amount;

    @Schema(description = "Currency code")
    private String currency;

    @Schema(description = "Current status of the payment")
    private PaymentStatus status;

    @Schema(description = "Failure reason if status is FAILED")
    private String failureReason;

    @Schema(description = "Creation timestamp (UTC)")
    private Instant createdAt;
}
