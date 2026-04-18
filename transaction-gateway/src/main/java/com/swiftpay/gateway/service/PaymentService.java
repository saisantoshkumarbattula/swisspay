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

        if (!idempotencyService.tryAcquire(request.getIdempotencyKey())) {
            return paymentRepository
                    .findByIdempotencyKey(request.getIdempotencyKey())
                    .map(this::toResponse)
                    .orElseThrow(() -> new DuplicatePaymentException(request.getIdempotencyKey()));
        }

        BigDecimal senderBalance = balanceCacheService.getCachedBalance(request.getSenderId())
                .orElseThrow(() -> new WalletNotFoundException(request.getSenderId().toString()));

        if (senderBalance.compareTo(request.getAmount()) < 0) {
            idempotencyService.markCompleted(request.getIdempotencyKey());
            throw new InsufficientFundsException(
                    String.format("Sender %s has insufficient funds. Available: %s, Requested: %s",
                            request.getSenderId(), senderBalance, request.getAmount()));
        }

        Payment payment = Payment.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .senderId(request.getSenderId())
                .receiverId(request.getReceiverId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.PENDING)
                .build();

        final Payment savedPayment = paymentRepository.save(payment);
        log.info("Payment persisted | transactionId={} status=PENDING", savedPayment.getId());

        PaymentInitiatedEvent event = PaymentInitiatedEvent.builder()
                .transactionId(savedPayment.getId())
                .idempotencyKey(savedPayment.getIdempotencyKey())
                .senderId(savedPayment.getSenderId())
                .receiverId(savedPayment.getReceiverId())
                .amount(savedPayment.getAmount())
                .currency(savedPayment.getCurrency())
                .initiatedAt(Instant.now())
                .build();

        kafkaTemplate.send(paymentInitiatedTopic, savedPayment.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PaymentInitiatedEvent | transactionId={}", savedPayment.getId(), ex);
                    } else {
                        log.info("PaymentInitiatedEvent published | transactionId={} partition={} offset={}",
                                savedPayment.getId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });

        idempotencyService.markCompleted(request.getIdempotencyKey());

        return toResponse(savedPayment);
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
