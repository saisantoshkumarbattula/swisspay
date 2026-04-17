package com.swiftpay.ledger.dto;

import com.swiftpay.ledger.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Transaction history entry")
public class TransactionHistoryEntry {

    @Schema(description = "Payment transaction ID")
    private UUID transactionId;

    @Schema(description = "The other party (counterpart user ID)")
    private UUID counterpartId;

    @Schema(description = "DEBIT (sent) or CREDIT (received)")
    private String direction;

    @Schema(description = "Transfer amount")
    private BigDecimal amount;

    @Schema(description = "Currency code")
    private String currency;

    @Schema(description = "Payment status")
    private PaymentStatus status;

    @Schema(description = "Payment creation timestamp")
    private Instant createdAt;
}
