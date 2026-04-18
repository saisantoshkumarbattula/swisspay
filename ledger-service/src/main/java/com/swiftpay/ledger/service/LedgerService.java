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

        if (payment.getStatus() == PaymentStatus.COMPLETED
                || payment.getStatus() == PaymentStatus.FAILED) {
            log.warn("Payment {} already in terminal state {}. Skipping.", event.getTransactionId(), payment.getStatus());
            return;
        }

        payment.setStatus(PaymentStatus.PROCESSING);
        paymentRepository.save(payment);

        try {
            var firstId  = event.getSenderId().compareTo(event.getReceiverId()) < 0 ? event.getSenderId()   : event.getReceiverId();
            var secondId = event.getSenderId().compareTo(event.getReceiverId()) < 0 ? event.getReceiverId() : event.getSenderId();

            Wallet first  = walletRepository.findByUserIdForUpdate(firstId)
                    .orElseThrow(() -> new IllegalStateException("Wallet not found: " + firstId));
            Wallet second = walletRepository.findByUserIdForUpdate(secondId)
                    .orElseThrow(() -> new IllegalStateException("Wallet not found: " + secondId));

            Wallet senderWallet   = event.getSenderId().equals(firstId)   ? first : second;
            Wallet receiverWallet = event.getReceiverId().equals(firstId) ? first : second;

            if (senderWallet.getBalance().compareTo(event.getAmount()) < 0) {
                throw new IllegalStateException(
                        String.format("Insufficient funds: sender %s has %s, needs %s",
                                event.getSenderId(), senderWallet.getBalance(), event.getAmount()));
            }

            BigDecimal senderBefore   = senderWallet.getBalance();
            BigDecimal receiverBefore = receiverWallet.getBalance();

            senderWallet.setBalance(senderBefore.subtract(event.getAmount()));
            receiverWallet.setBalance(receiverBefore.add(event.getAmount()));
            walletRepository.save(senderWallet);
            walletRepository.save(receiverWallet);

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

            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);

            log.info("Payment COMPLETED | transactionId={} senderBalance={} receiverBalance={}",
                    event.getTransactionId(), senderWallet.getBalance(), receiverWallet.getBalance());

            emitResult(event, "COMPLETED", null, completedTopic);

        } catch (Exception ex) {
            log.error("Payment FAILED | transactionId={} reason={}", event.getTransactionId(), ex.getMessage(), ex);
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);
            emitResult(event, "FAILED", ex.getMessage(), failedTopic);
            throw ex;
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
