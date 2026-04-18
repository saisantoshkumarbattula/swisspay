package com.swiftpay.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"payment-initiated", "payment-completed", "payment-failed"})
@ActiveProfiles("test")
class LedgerServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
