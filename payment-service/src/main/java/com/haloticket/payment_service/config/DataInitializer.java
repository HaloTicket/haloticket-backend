package com.haloticket.payment_service.config;

import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.entity.PaymentTransaction;
import com.haloticket.payment_service.entity.Payout;
import com.haloticket.payment_service.enums.PaymentMethod;
import com.haloticket.payment_service.enums.PaymentProvider;
import com.haloticket.payment_service.enums.PaymentStatus;
import com.haloticket.payment_service.enums.PayoutStatus;
import com.haloticket.payment_service.enums.TransactionStatus;
import com.haloticket.payment_service.repository.PaymentRepository;
import com.haloticket.payment_service.repository.PayoutRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final PaymentRepository paymentRepository;
    private final PayoutRepository payoutRepository;

    @PersistenceContext
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        try {
            entityManager.createNativeQuery("ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_method_check").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_provider_check").executeUpdate();
            log.info("Successfully dropped legacy payment check constraints (payments_method_check, payments_provider_check).");
        } catch (Exception e) {
            log.warn("Could not drop legacy payment constraints: {}", e.getMessage());
        }

        if (paymentRepository.count() > 0) {
            log.info("Payment database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for Payment Service...");

        // 1. Successful VNPay Payment
        Payment payment1 = Payment.builder()
                .bookingId("booking-001")
                .amount(2800000.0)
                .method(PaymentMethod.VNPAY)
                .provider(PaymentProvider.VNPAY_GATEWAY)
                .status(PaymentStatus.SUCCESS)
                .build();
        entityManager.persist(payment1);

        PaymentTransaction tx1 = PaymentTransaction.builder()
                .payment(payment1)
                .gatewayRefId("VNPAY_REF_20260829001")
                .payloadJson("{\"vnp_ResponseCode\":\"00\",\"vnp_TransactionNo\":\"14589230\",\"vnp_BankCode\":\"NCB\"}")
                .status(TransactionStatus.SUCCESS)
                .build();
        entityManager.persist(tx1);

        // 2. Pending Payment
        Payment payment2 = Payment.builder()
                .bookingId("booking-002")
                .amount(800000.0)
                .method(PaymentMethod.ZALOPAY)
                .provider(PaymentProvider.ZALOPAY_GATEWAY)
                .status(PaymentStatus.PENDING)
                .build();
        entityManager.persist(payment2);

        // 3. Initialize Sample Payouts for testing if empty
        if (payoutRepository.count() == 0) {
            log.info("Seeding sample Payout records for UI testing...");

            // PENDING payout
            Payout payout1 = Payout.builder()
                    .eventId("EVT-123")
                    .organizerUserId("org-001")
                    .grossAmount(500000000.0)
                    .platformFee(25000000.0)
                    .netAmount(475000000.0)
                    .status(PayoutStatus.PENDING)
                    .createdAt(LocalDateTime.now().minusHours(4))
                    .updatedAt(LocalDateTime.now().minusHours(4))
                    .build();
            payoutRepository.save(payout1);

            // COMPLETED payout 1
            Payout payout2 = Payout.builder()
                    .eventId("EVT-456")
                    .organizerUserId("org-002")
                    .grossAmount(120000000.0)
                    .platformFee(6000000.0)
                    .netAmount(114000000.0)
                    .status(PayoutStatus.COMPLETED)
                    .transferredAt(LocalDateTime.now().minusDays(2))
                    .createdAt(LocalDateTime.now().minusDays(3))
                    .updatedAt(LocalDateTime.now().minusDays(2))
                    .build();
            payoutRepository.save(payout2);

            // COMPLETED payout 2
            Payout payout3 = Payout.builder()
                    .eventId("EVT-789")
                    .organizerUserId("org-001")
                    .grossAmount(850000000.0)
                    .platformFee(42500000.0)
                    .netAmount(807500000.0)
                    .status(PayoutStatus.COMPLETED)
                    .transferredAt(LocalDateTime.now().minusDays(5))
                    .createdAt(LocalDateTime.now().minusDays(6))
                    .updatedAt(LocalDateTime.now().minusDays(5))
                    .build();
            payoutRepository.save(payout3);

            // DISPUTE payout
            Payout payout4 = Payout.builder()
                    .eventId("EVT-101")
                    .organizerUserId("org-003")
                    .grossAmount(80000000.0)
                    .platformFee(4000000.0)
                    .netAmount(76000000.0)
                    .status(PayoutStatus.DISPUTE)
                    .createdAt(LocalDateTime.now().minusDays(1))
                    .updatedAt(LocalDateTime.now().minusHours(10))
                    .build();
            payoutRepository.save(payout4);

            log.info("Initialized 4 sample Payout records successfully!");
        }

        log.info("Payment Service sample data initialized successfully! Created payment records, transactions and payouts.");
    }
}
