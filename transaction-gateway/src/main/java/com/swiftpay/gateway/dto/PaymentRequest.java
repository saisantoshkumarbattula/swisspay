package com.swiftpay.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Inbound request body for POST /v1/payments.
 */
@Data
@Schema(description = "Payment initiation request")
public class PaymentRequest {

    @Schema(description = "Idempotency key (UUID). Same key within 24 h returns cached result.", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
    @NotBlank(message = "idempotencyKey must not be blank")
    @Size(max = 64, message = "idempotencyKey must be at most 64 characters")
    private String idempotencyKey;

    @Schema(description = "UUID of the wallet sending the funds", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
    @NotNull(message = "senderId is required")
    private UUID senderId;

    @Schema(description = "UUID of the wallet receiving the funds", example = "b1ffcd88-1d1c-5fg9-cc7e-7cc0ce491b22")
    @NotNull(message = "receiverId is required")
    private UUID receiverId;

    @Schema(description = "Transfer amount (positive, up to 4 decimal places)", example = "250.00")
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be greater than 0")
    @Digits(integer = 15, fraction = 4, message = "amount must have at most 15 integer and 4 fractional digits")
    private BigDecimal amount;

    @Schema(description = "ISO 4217 currency code", example = "USD")
    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be exactly 3 characters")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an uppercase ISO 4217 code")
    private String currency;
}
