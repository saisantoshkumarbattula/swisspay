package com.swiftpay.ledger.controller;

import com.swiftpay.ledger.domain.Payment;
import com.swiftpay.ledger.domain.Wallet;
import com.swiftpay.ledger.dto.TransactionHistoryEntry;
import com.swiftpay.ledger.repository.PaymentRepository;
import com.swiftpay.ledger.repository.WalletRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Reporting endpoint for transaction history (Service B).
 */
@RestController
@RequestMapping("/v1/ledger")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Ledger", description = "Transaction history and balance reporting")
public class LedgerController {

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;

    @Operation(summary = "Get transaction history for a user",
            description = "Returns a paginated list of sent and received payments for the given user ID.")
    @GetMapping("/transactions/{userId}")
    public ResponseEntity<Page<TransactionHistoryEntry>> getTransactionHistory(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Payment> payments = paymentRepository
                .findBySenderIdOrReceiverIdOrderByCreatedAtDesc(userId, userId, pageable);

        Page<TransactionHistoryEntry> result = payments.map(p -> {
            boolean isSender = userId.equals(p.getSenderId());
            return TransactionHistoryEntry.builder()
                    .transactionId(p.getId())
                    .counterpartId(isSender ? p.getReceiverId() : p.getSenderId())
                    .direction(isSender ? "DEBIT" : "CREDIT")
                    .amount(p.getAmount())
                    .currency(p.getCurrency())
                    .status(p.getStatus())
                    .createdAt(p.getCreatedAt())
                    .build();
        });

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get wallet balance for a user")
    @GetMapping("/balance/{userId}")
    public ResponseEntity<?> getBalance(@PathVariable UUID userId) {
        return walletRepository.findById(userId)
                .map(w -> ResponseEntity.ok(new BalanceResponse(w.getUserId(), w.getBalance(), w.getCurrency())))
                .orElse(ResponseEntity.notFound().build());
    }

    record BalanceResponse(UUID userId, java.math.BigDecimal balance, String currency) {}
}
