package com.swiftpay.gateway;

import com.swiftpay.gateway.domain.PaymentStatus;
import com.swiftpay.gateway.dto.PaymentRequest;
import com.swiftpay.gateway.dto.PaymentResponse;
import com.swiftpay.gateway.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Payment Gateway.
 * Uses Testcontainers for PostgreSQL and EmbeddedKafka for Kafka.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"payment-initiated", "payment-completed", "payment-failed"})
@ActiveProfiles("test")
@Sql(scripts = "/seed-wallets.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PaymentGatewayIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("swiftpay_test")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IdempotencyService idempotencyService;

    private static final UUID SENDER_ID   = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
    private static final UUID RECEIVER_ID = UUID.fromString("b1ffcd88-1d1c-5fe9-cc7e-7cc0ce491b22");

    @BeforeEach
    void setUp() {
        // Nothing to reset — each test uses a fresh idempotency key
    }

    @Test
    @DisplayName("POST /v1/payments → 202 ACCEPTED for a valid request")
    void shouldInitiatePaymentSuccessfully() {
        PaymentRequest request = buildRequest(UUID.randomUUID().toString(), BigDecimal.valueOf(100));

        ResponseEntity<PaymentResponse> response =
                restTemplate.postForEntity("/v1/payments", request, PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.getBody().getSenderId()).isEqualTo(SENDER_ID);
    }

    @Test
    @DisplayName("POST /v1/payments → 409 CONFLICT on duplicate idempotency key")
    void shouldReturn409OnDuplicateIdempotencyKey() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = buildRequest(idempotencyKey, BigDecimal.valueOf(50));

        // First request — should succeed
        restTemplate.postForEntity("/v1/payments", request, PaymentResponse.class);

        // Second request with same key — should fail
        ResponseEntity<String> secondResponse =
                restTemplate.postForEntity("/v1/payments", request, String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /v1/payments → 422 UNPROCESSABLE when amount exceeds balance")
    void shouldReturn422WhenInsufficientFunds() {
        PaymentRequest request = buildRequest(UUID.randomUUID().toString(), BigDecimal.valueOf(999_999_999));

        ResponseEntity<String> response =
                restTemplate.postForEntity("/v1/payments", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("POST /v1/payments → 400 BAD REQUEST for missing fields")
    void shouldReturn400WhenMissingFields() {
        PaymentRequest request = new PaymentRequest(); // empty

        ResponseEntity<String> response =
                restTemplate.postForEntity("/v1/payments", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /v1/payments/{id} → 200 OK for existing payment")
    void shouldGetPaymentById() {
        // First create a payment
        PaymentRequest request = buildRequest(UUID.randomUUID().toString(), BigDecimal.valueOf(25));
        ResponseEntity<PaymentResponse> created =
                restTemplate.postForEntity("/v1/payments", request, PaymentResponse.class);
        UUID transactionId = created.getBody().getTransactionId();

        // Then get it
        ResponseEntity<PaymentResponse> response =
                restTemplate.getForEntity("/v1/payments/" + transactionId, PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTransactionId()).isEqualTo(transactionId);
    }

    private PaymentRequest buildRequest(String idempotencyKey, BigDecimal amount) {
        PaymentRequest req = new PaymentRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setSenderId(SENDER_ID);
        req.setReceiverId(RECEIVER_ID);
        req.setAmount(amount);
        req.setCurrency("USD");
        return req;
    }
}
