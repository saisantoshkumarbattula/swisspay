package com.swiftpay.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response body following RFC 7807 / Problem Details conventions.
 */
@Data
@Builder
@Schema(description = "Standard error response")
public class ErrorResponse {

    @Schema(description = "HTTP status code")
    private int status;

    @Schema(description = "Short error code", example = "VALIDATION_ERROR")
    private String error;

    @Schema(description = "Human-readable message")
    private String message;

    @Schema(description = "Field-level validation errors")
    private List<FieldError> fieldErrors;

    @Schema(description = "Timestamp of the error")
    private Instant timestamp;

    @Data
    @Builder
    public static class FieldError {
        private String field;
        private String message;
    }
}
