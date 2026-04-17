package com.swiftpay.gateway.service;

import com.swiftpay.gateway.domain.Payment;
import com.swiftpay.gateway.domain.PaymentStatus;
import com.swiftpay.gateway.dto.PaymentRequest;
import com.swiftpay.gateway.dto.PaymentResponse;
import com.swiftpay.gateway.event.PaymentInitiatedEvent;
import com.swiftpay.gateway.event.PaymentResultEvent;
import com.swiftpay.gateway.exception.DuplicatePaymentException;
import com.swiftpay.gateway.exception.InsufficientFundsException;
import com.swiftpay.gateway.exception.WalletNotFoundException;
import com.swiftpay.gateway.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Core business logic for payment initiation.
 *
 * <p>Flow:
 * <ol>
 *   <li>Check idempotency key against Redis  →  reject duplicates</li>
 *   <li>Read (cached) sender balance         →  reject if insufficient</li>
 *   <li>Persist PENDING record in PostgreSQL</li>
 *   <li>Emit {@link PaymentInitiatedEvent} to Kafka</li>
 *   <li>Mark idempotency key as COMPLETED in Redis</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyService idempotencyService;
    private final BalanceCacheService balanceCacheService;
    private final KafkaTemplate<String, PaymentInitiatedEvent> kafkaTemplate;

    @Value("${swiftpay.kafka.topic.payment-initiated:payment-initiated}")
    private String paymentInitiatedTopic;

    /**
     * Initiates a payment request end-to-end.
     */
    @Transactional
    public PaymentResponse initiatePayment(PaymentRequest request) {
        log.info("Initiating payment | idempotencyKey={} sender={} receiver={} amount={} {}",
                request.getIdempotencyKey(), request.getSenderId(), request.getReceiverId(),
                request.getAmount(), request.getCurrency());

        // 1. Idempotency guard — Redis SET NX
        if (!idempotencyService.tryAcquire(request.getIdempotencyKey())) {
            // Return the existing payment if we can find it
            return paymentRepository
                    .findByIdempotencyKey(request.getIdempotencyKey())
                    .map(this::toResponse)
                    .orElseThrow(() -> new DuplicatePaymentException(request.getIdempotencyKey()));
        }

        // 2. Balance validation (Redis cached, fallback to DB)
        BigDecimal senderBalance = balanceCacheService.getCachedBalance(request.getSenderId())
                .orElseThrow(() -> new WalletNotFoundException(request.getSenderId().toString()));

        if (senderBalance.compareTo(request.getAmount()) < 0) {
            // Release the idempotency lock so the user can retry with a valid amount
            idempotencyService.markCompleted(request.getIdempotencyKey());
            throw new InsufficientFundsException(
                    String.format("Sender %s has insufficient funds. Available: %s, Requested: %s",
                            request.getSenderId(), senderBalance, request.getAmount()));
        }

        // 3. Persist with PENDING status
        Payment payment = Payment.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .senderId(request.getSenderId())
                .receiverId(request.getReceiverId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.PENDING)
                .build();
        payment = paymentRepository.save(payment);
        log.info("Payment persisted | transactionId={} status=PENDING", payment.getId());

        // 4. Emit Kafka event
        PaymentInitiatedEvent event = PaymentInitiatedEvent.builder()
                .transactionId(payment.getId())
                .idempotencyKey(payment.getIdempotencyKey())
                .senderId(payment.getSenderId())
                .receiverId(payment.getReceiverId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .initiatedAt(Instant.now())
                .build();

        kafkaTemplate.send(paymentInitiatedTopic, payment.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PaymentInitiatedEvent | transactionId={}", payment.getId(), ex);
                    } else {
                        log.info("PaymentInitiatedEvent published | transactionId={} partition={} offset={}",
                                payment.getId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });

        // 5. Mark idempotency key as completed
        idempotencyService.markCompleted(request.getIdempotencyKey());

        return toResponse(payment);
    }

    /**
     * Updates payment status from a result event received from Kafka.
     */
    @Transactional
    public void applyPaymentResult(PaymentResultEvent event) {
        paymentRepository.findById(event.getTransactionId()).ifPresent(payment -> {
            PaymentStatus newStatus = "COMPLETED".equals(event.getStatus())
                    ? PaymentStatus.COMPLETED
                    : PaymentStatus.FAILED;
            payment.setStatus(newStatus);
            payment.setFailureReason(event.getFailureReason());
            paymentRepository.save(payment);

            // Evict cached balances after a successful transfer
            if (newStatus == PaymentStatus.COMPLETED) {
                balanceCacheService.evictBalance(event.getSenderId());
                balanceCacheService.evictBalance(event.getReceiverId());
            }

            log.info("Payment status updated | transactionId={} newStatus={}", event.getTransactionId(), newStatus);
        });
    }

    /**
     * Retrieves a payment by its transaction ID.
     */
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID transactionId) {
        return paymentRepository.findById(transactionId)
                .map(this::toResponse)
                .orElseThrow(() -> new WalletNotFoundException("Payment not found: " + transactionId));
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
                .transactionId(payment.getId())
                .idempotencyKey(payment.getIdempotencyKey())
                .senderId(payment.getSenderId())
                .receiverId(payment.getReceiverId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .failureReason(payment.getFailureReason())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
