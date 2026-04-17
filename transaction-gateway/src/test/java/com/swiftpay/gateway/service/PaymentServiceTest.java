package com.swiftpay.gateway.service;

import com.swiftpay.gateway.domain.Payment;
import com.swiftpay.gateway.domain.PaymentStatus;
import com.swiftpay.gateway.dto.PaymentRequest;
import com.swiftpay.gateway.dto.PaymentResponse;
import com.swiftpay.gateway.event.PaymentInitiatedEvent;
import com.swiftpay.gateway.exception.DuplicatePaymentException;
import com.swiftpay.gateway.exception.InsufficientFundsException;
import com.swiftpay.gateway.exception.WalletNotFoundException;
import com.swiftpay.gateway.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PaymentService using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private IdempotencyService idempotencyService;
    @Mock private BalanceCacheService balanceCacheService;
    @Mock private KafkaTemplate<String, PaymentInitiatedEvent> kafkaTemplate;

    @InjectMocks private PaymentService paymentService;

    private static final UUID SENDER_ID   = UUID.randomUUID();
    private static final UUID RECEIVER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Inject @Value fields via reflection (they default to null in unit tests)
        try {
            var field = PaymentService.class.getDeclaredField("paymentInitiatedTopic");
            field.setAccessible(true);
            field.set(paymentService, "payment-initiated");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should initiate payment successfully when funds are sufficient")
    void shouldInitiatePayment() {
        PaymentRequest request = buildRequest("key-001", BigDecimal.valueOf(100));
        Payment saved = buildPayment(request);

        when(idempotencyService.tryAcquire("key-001")).thenReturn(true);
        when(balanceCacheService.getCachedBalance(SENDER_ID)).thenReturn(Optional.of(BigDecimal.valueOf(5000)));
        when(paymentRepository.save(any())).thenReturn(saved);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        PaymentResponse response = paymentService.initiatePayment(request);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(kafkaTemplate).send(eq("payment-initiated"), anyString(), any(PaymentInitiatedEvent.class));
        verify(idempotencyService).markCompleted("key-001");
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when balance is too low")
    void shouldThrowOnInsufficientFunds() {
        PaymentRequest request = buildRequest("key-002", BigDecimal.valueOf(999_999));

        when(idempotencyService.tryAcquire("key-002")).thenReturn(true);
        when(balanceCacheService.getCachedBalance(SENDER_ID)).thenReturn(Optional.of(BigDecimal.valueOf(100)));

        assertThatThrownBy(() -> paymentService.initiatePayment(request))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return existing payment on duplicate idempotency key")
    void shouldReturnExistingOnDuplicate() {
        PaymentRequest request = buildRequest("key-003", BigDecimal.valueOf(50));
        Payment existing = buildPayment(request);
        existing.setStatus(PaymentStatus.PENDING);

        when(idempotencyService.tryAcquire("key-003")).thenReturn(false);
        when(paymentRepository.findByIdempotencyKey("key-003")).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.initiatePayment(request);
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Should throw WalletNotFoundException when sender wallet missing")
    void shouldThrowWhenSenderMissing() {
        PaymentRequest request = buildRequest("key-004", BigDecimal.valueOf(50));

        when(idempotencyService.tryAcquire("key-004")).thenReturn(true);
        when(balanceCacheService.getCachedBalance(SENDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.initiatePayment(request))
                .isInstanceOf(WalletNotFoundException.class);
    }

    private PaymentRequest buildRequest(String key, BigDecimal amount) {
        PaymentRequest r = new PaymentRequest();
        r.setIdempotencyKey(key);
        r.setSenderId(SENDER_ID);
        r.setReceiverId(RECEIVER_ID);
        r.setAmount(amount);
        r.setCurrency("USD");
        return r;
    }

    private Payment buildPayment(PaymentRequest request) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(request.getIdempotencyKey())
                .senderId(request.getSenderId())
                .receiverId(request.getReceiverId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.PENDING)
                .build();
    }
}
