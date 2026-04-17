package com.swiftpay.ledger.service;

import com.swiftpay.ledger.domain.*;
import com.swiftpay.ledger.event.PaymentInitiatedEvent;
import com.swiftpay.ledger.event.PaymentResultEvent;
import com.swiftpay.ledger.repository.LedgerEntryRepository;
import com.swiftpay.ledger.repository.PaymentRepository;
import com.swiftpay.ledger.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private KafkaTemplate<String, PaymentResultEvent> kafkaTemplate;

    @InjectMocks private LedgerService ledgerService;

    private static final UUID SENDER_ID   = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
    private static final UUID RECEIVER_ID = UUID.fromString("b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22");
    private static final UUID TX_ID       = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        var completedField = LedgerService.class.getDeclaredField("completedTopic");
        completedField.setAccessible(true);
        completedField.set(ledgerService, "payment-completed");

        var failedField = LedgerService.class.getDeclaredField("failedTopic");
        failedField.setAccessible(true);
        failedField.set(ledgerService, "payment-failed");
    }

    @Test
    @DisplayName("Should perform atomic debit/credit and emit COMPLETED event")
    void shouldCompletePaymentSuccessfully() {
        PaymentInitiatedEvent event = buildEvent(BigDecimal.valueOf(100));
        Payment payment = buildPendingPayment(event);

        // Wallet locks - order by UUID compareTo
        Wallet senderWallet   = buildWallet(SENDER_ID,   BigDecimal.valueOf(500));
        Wallet receiverWallet = buildWallet(RECEIVER_ID, BigDecimal.valueOf(100));

        when(paymentRepository.findById(TX_ID)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenReturn(payment);
        
        // Mock both lock calls - the service locks in UUID-sorted order
        when(walletRepository.findByUserIdForUpdate(SENDER_ID)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByUserIdForUpdate(RECEIVER_ID)).thenReturn(Optional.of(receiverWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        ledgerService.processPayment(event);

        // Verify debit and credit
        ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository, times(2)).save(walletCaptor.capture());
        
        // Verify two ledger entries
        verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));
        
        // Verify COMPLETED event emitted
        verify(kafkaTemplate).send(eq("payment-completed"), anyString(), any(PaymentResultEvent.class));
    }

    @Test
    @DisplayName("Should emit FAILED event when sender has insufficient funds")
    void shouldFailWhenInsufficientFunds() {
        PaymentInitiatedEvent event = buildEvent(BigDecimal.valueOf(9999));
        Payment payment = buildPendingPayment(event);

        Wallet senderWallet   = buildWallet(SENDER_ID,   BigDecimal.valueOf(10));  // too low
        Wallet receiverWallet = buildWallet(RECEIVER_ID, BigDecimal.valueOf(100));

        when(paymentRepository.findById(TX_ID)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenReturn(payment);
        when(walletRepository.findByUserIdForUpdate(SENDER_ID)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByUserIdForUpdate(RECEIVER_ID)).thenReturn(Optional.of(receiverWallet));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> ledgerService.processPayment(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient funds");

        verify(kafkaTemplate).send(eq("payment-failed"), anyString(), any(PaymentResultEvent.class));
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should skip already-COMPLETED payments (idempotency guard)")
    void shouldSkipAlreadyCompletedPayment() {
        PaymentInitiatedEvent event = buildEvent(BigDecimal.valueOf(50));
        Payment payment = buildPendingPayment(event);
        payment.setStatus(PaymentStatus.COMPLETED);

        when(paymentRepository.findById(TX_ID)).thenReturn(Optional.of(payment));

        ledgerService.processPayment(event);

        verify(walletRepository, never()).findByUserIdForUpdate(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private PaymentInitiatedEvent buildEvent(BigDecimal amount) {
        return PaymentInitiatedEvent.builder()
                .transactionId(TX_ID)
                .idempotencyKey("test-key-" + TX_ID)
                .senderId(SENDER_ID)
                .receiverId(RECEIVER_ID)
                .amount(amount)
                .currency("USD")
                .initiatedAt(Instant.now())
                .build();
    }

    private Payment buildPendingPayment(PaymentInitiatedEvent event) {
        return Payment.builder()
                .id(event.getTransactionId())
                .idempotencyKey(event.getIdempotencyKey())
                .senderId(event.getSenderId())
                .receiverId(event.getReceiverId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .status(PaymentStatus.PENDING)
                .build();
    }

    private Wallet buildWallet(UUID userId, BigDecimal balance) {
        return Wallet.builder()
                .userId(userId)
                .balance(balance)
                .currency("USD")
                .version(0L)
                .build();
    }
}
