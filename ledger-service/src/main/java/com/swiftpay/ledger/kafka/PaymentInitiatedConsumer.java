package com.swiftpay.ledger.kafka;

import com.swiftpay.ledger.event.PaymentInitiatedEvent;
import com.swiftpay.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the Ledger Service.
 * Consumes PaymentInitiated events and delegates to LedgerService for atomic processing.
 *
 * <p>Uses @RetryableTopic with exponential back-off to handle transient DB outages.
 * After max retries, the message routes to the Dead Letter Topic (DLT).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentInitiatedConsumer {

    private final LedgerService ledgerService;

    @RetryableTopic(
            attempts = "5",
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 30000),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = "-dlt",
            autoCreateTopics = "true"
    )
    @KafkaListener(
            topics = "${swiftpay.kafka.topic.payment-initiated:payment-initiated}",
            groupId = "ledger-processor",
            containerFactory = "paymentInitiatedListenerContainerFactory"
    )
    public void onPaymentInitiated(PaymentInitiatedEvent event) {
        log.info("Received PaymentInitiatedEvent | transactionId={}", event.getTransactionId());
        ledgerService.processPayment(event);
    }
}
