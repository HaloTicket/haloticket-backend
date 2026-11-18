package com.haloticket.payment_service.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Chạy khi khởi động: gỡ các ràng buộc check cũ của bảng {@code payments} (phương thức và nhà cung cấp) để cho phép giá trị mới. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    @PersistenceContext
    private final EntityManager entityManager;

    /**
     * Gỡ ràng buộc {@code payments_method_check} và {@code payments_provider_check} nếu còn; lỗi chỉ ghi cảnh báo, không chặn khởi động.
     *
     * @param args tham số dòng lệnh (không dùng)
     */
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

        log.info("Payment Service initialized. Mock data initialization has been removed.");
    }
}
