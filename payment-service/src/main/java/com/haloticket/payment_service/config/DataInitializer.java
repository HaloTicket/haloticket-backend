package com.haloticket.payment_service.config;

import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.entity.PaymentTransaction;
import com.haloticket.payment_service.enums.PaymentMethod;
import com.haloticket.payment_service.enums.PaymentProvider;
import com.haloticket.payment_service.enums.PaymentStatus;
import com.haloticket.payment_service.enums.TransactionStatus;
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

        log.info("Payment Service sample data initialized successfully! Created payment records and payment transactions.");
    }
}
