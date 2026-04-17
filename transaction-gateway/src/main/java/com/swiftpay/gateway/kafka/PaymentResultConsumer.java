package com.swiftpay.gateway.kafka;

import com.swiftpay.gateway.event.PaymentResultEvent;
import com.swiftpay.gateway.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer in the Gateway.
 * Listens for PaymentCompleted / PaymentFailed events from the Ledger Service
 * and updates the local payment status in PostgreSQL.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentResultConsumer {

    private final PaymentService paymentService;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = "-dlt"
    )
    @KafkaListener(
            topics = {"${swiftpay.kafka.topic.payment-completed:payment-completed}",
                      "${swiftpay.kafka.topic.payment-failed:payment-failed}"},
            groupId = "gateway-result-consumer",
            containerFactory = "resultEventListenerContainerFactory"
    )
    public void onPaymentResult(PaymentResultEvent event) {
        log.info("Received PaymentResultEvent | transactionId={} status={}", event.getTransactionId(), event.getStatus());
        try {
            paymentService.applyPaymentResult(event);
        } catch (Exception ex) {
            log.error("Error processing PaymentResultEvent | transactionId={}", event.getTransactionId(), ex);
            throw ex; // re-throw to trigger @RetryableTopic
        }
    }
}
