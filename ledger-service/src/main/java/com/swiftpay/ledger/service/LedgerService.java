package com.swiftpay.ledger.service;

import com.swiftpay.ledger.domain.*;
import com.swiftpay.ledger.event.PaymentInitiatedEvent;
import com.swiftpay.ledger.event.PaymentResultEvent;
import com.swiftpay.ledger.repository.LedgerEntryRepository;
import com.swiftpay.ledger.repository.PaymentRepository;
import com.swiftpay.ledger.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Core ledger processing service.
 *
 * <p>For every PaymentInitiatedEvent it:
 * <ol>
 *   <li>Acquires PESSIMISTIC_WRITE locks on sender + receiver wallets</li>
 *   <li>Validates that sender still has sufficient balance</li>
 *   <li>Performs atomic debit/credit inside a single DB transaction</li>
 *   <li>Writes two immutable {@link LedgerEntry} records (double-entry)</li>
 *   <li>Marks the payment as COMPLETED (or FAILED)</li>
 *   <li>Emits a PaymentCompleted or PaymentFailed event to Kafka</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final KafkaTemplate<String, PaymentResultEvent> kafkaTemplate;

    @Value("${swiftpay.kafka.topic.payment-completed:payment-completed}")
    private String completedTopic;

    @Value("${swiftpay.kafka.topic.payment-failed:payment-failed}")
    private String failedTopic;

    @Transactional
    public void processPayment(PaymentInitiatedEvent event) {
        log.info("Processing payment | transactionId={} sender={} receiver={} amount={}",
                event.getTransactionId(), event.getSenderId(), event.getReceiverId(), event.getAmount());

        // 1. Upsert Payment record (may not exist in Ledger DB yet)
        Payment payment = paymentRepository.findById(event.getTransactionId())
                .orElseGet(() -> {
                    Payment p = Payment.builder()
                            .id(event.getTransactionId())
                            .idempotencyKey(event.getIdempotencyKey())
                            .senderId(event.getSenderId())
                            .receiverId(event.getReceiverId())
                            .amount(event.getAmount())
                            .currency(event.getCurrency())
                            .status(PaymentStatus.PROCESSING)
                            .build();
                    return paymentRepository.save(p);
                });

        // Idempotency guard: skip already-processed events
        if (payment.getStatus() == PaymentStatus.COMPLETED
                || payment.getStatus() == PaymentStatus.FAILED) {
            log.warn("Payment {} already in terminal state {}. Skipping.", event.getTransactionId(), payment.getStatus());
            return;
        }

        payment.setStatus(PaymentStatus.PROCESSING);
        paymentRepository.save(payment);

        try {
            // 2. Acquire PESSIMISTIC_WRITE locks (ordered by ID to prevent deadlocks)
            var firstId  = event.getSenderId().compareTo(event.getReceiverId()) < 0 ? event.getSenderId()   : event.getReceiverId();
            var secondId = event.getSenderId().compareTo(event.getReceiverId()) < 0 ? event.getReceiverId() : event.getSenderId();

            Wallet first  = walletRepository.findByUserIdForUpdate(firstId)
                    .orElseThrow(() -> new IllegalStateException("Wallet not found: " + firstId));
            Wallet second = walletRepository.findByUserIdForUpdate(secondId)
                    .orElseThrow(() -> new IllegalStateException("Wallet not found: " + secondId));

            Wallet senderWallet   = event.getSenderId().equals(firstId)   ? first : second;
            Wallet receiverWallet = event.getReceiverId().equals(firstId) ? first : second;

            // 3. Final balance check (read-after-lock)
            if (senderWallet.getBalance().compareTo(event.getAmount()) < 0) {
                throw new IllegalStateException(
                        String.format("Insufficient funds: sender %s has %s, needs %s",
                                event.getSenderId(), senderWallet.getBalance(), event.getAmount()));
            }

            BigDecimal senderBefore   = senderWallet.getBalance();
            BigDecimal receiverBefore = receiverWallet.getBalance();

            // 4. Atomic debit/credit
            senderWallet.setBalance(senderBefore.subtract(event.getAmount()));
            receiverWallet.setBalance(receiverBefore.add(event.getAmount()));
            walletRepository.save(senderWallet);
            walletRepository.save(receiverWallet);

            // 5. Double-entry ledger records
            ledgerEntryRepository.save(LedgerEntry.builder()
                    .paymentId(event.getTransactionId())
                    .walletId(event.getSenderId())
                    .entryType(LedgerEntry.EntryType.DEBIT)
                    .amount(event.getAmount())
                    .balanceBefore(senderBefore)
                    .balanceAfter(senderWallet.getBalance())
                    .build());

            ledgerEntryRepository.save(LedgerEntry.builder()
                    .paymentId(event.getTransactionId())
                    .walletId(event.getReceiverId())
                    .entryType(LedgerEntry.EntryType.CREDIT)
                    .amount(event.getAmount())
                    .balanceBefore(receiverBefore)
                    .balanceAfter(receiverWallet.getBalance())
                    .build());

            // 6. Mark payment as COMPLETED
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);

            log.info("Payment COMPLETED | transactionId={} senderBalance={} receiverBalance={}",
                    event.getTransactionId(), senderWallet.getBalance(), receiverWallet.getBalance());

            // 7. Emit success event
            emitResult(event, "COMPLETED", null, completedTopic);

        } catch (Exception ex) {
            log.error("Payment FAILED | transactionId={} reason={}", event.getTransactionId(), ex.getMessage(), ex);
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);
            emitResult(event, "FAILED", ex.getMessage(), failedTopic);
            throw ex; // Re-throw to trigger Kafka retry if configured
        }
    }

    private void emitResult(PaymentInitiatedEvent event, String status, String reason, String topic) {
        PaymentResultEvent resultEvent = PaymentResultEvent.builder()
                .transactionId(event.getTransactionId())
                .status(status)
                .failureReason(reason)
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .senderId(event.getSenderId())
                .receiverId(event.getReceiverId())
                .processedAt(Instant.now())
                .build();

        kafkaTemplate.send(topic, event.getTransactionId().toString(), resultEvent)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send {} event for transactionId={}", status, event.getTransactionId(), ex);
                    } else {
                        log.info("Emitted {} event | transactionId={}", status, event.getTransactionId());
                    }
                });
    }
}
