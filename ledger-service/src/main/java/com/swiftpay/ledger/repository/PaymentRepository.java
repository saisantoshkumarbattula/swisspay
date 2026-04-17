package com.swiftpay.ledger.repository;

import com.swiftpay.ledger.domain.Payment;
import com.swiftpay.ledger.domain.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Page<Payment> findBySenderIdOrReceiverIdOrderByCreatedAtDesc(UUID senderId, UUID receiverId, Pageable pageable);
    Page<Payment> findBySenderIdOrderByCreatedAtDesc(UUID senderId, Pageable pageable);
    Page<Payment> findByReceiverIdOrderByCreatedAtDesc(UUID receiverId, Pageable pageable);
    long countByStatus(PaymentStatus status);
}
