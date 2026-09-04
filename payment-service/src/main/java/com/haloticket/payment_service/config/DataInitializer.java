package com.haloticket.payment_service.config;

import com.haloticket.payment_service.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final PaymentRepository paymentRepository;

    @PersistenceContext
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        if (paymentRepository.existsById("payment-001")) {
            log.info("Payment database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for Payment Service...");

        // Use native SQL to bypass Hibernate @GeneratedValue(UUID) lifecycle with manual IDs
        entityManager.createNativeQuery("""
                INSERT INTO payments (id, booking_id, amount, method, provider, status, created_at, updated_at)
                VALUES
                    ('payment-001', 'booking-001', 2800000.0, 'VNPAY',   'VNPAY_GATEWAY',   'SUCCESS', NOW(), NOW()),
                    ('payment-002', 'booking-002', 800000.0,  'ZALOPAY', 'ZALOPAY_GATEWAY', 'PENDING', NOW(), NOW())
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();

        entityManager.createNativeQuery("""
                INSERT INTO payment_transactions (id, payment_id, gateway_ref_id, payload_json, status)
                VALUES
                    ('tx-001', 'payment-001', 'VNPAY_REF_20260829001',
                     '{"vnp_ResponseCode":"00","vnp_TransactionNo":"14589230","vnp_BankCode":"NCB"}',
                     'SUCCESS')
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();

        log.info("Payment Service sample data initialized successfully! Created payment records and payment transactions.");
    }
}
