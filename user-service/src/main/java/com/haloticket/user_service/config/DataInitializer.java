package com.haloticket.user_service.config;

import com.haloticket.user_service.entity.EkycProfile;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.EkycDocumentType;
import com.haloticket.user_service.enums.EkycVerificationStatus;
import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;

    @PersistenceContext
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsById("11111111-1111-1111-1111-111111111111")) {
            log.info("User database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for User Service...");

        // 1. Admin User
        User admin = User.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .keycloakId("kc-admin-001")
                .email("admin@haloticket.vn")
                .fullName("Trần Long Vũ (Admin)")
                .phoneNumber("0901234567")
                .avatarUrl("https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=200")
                .isActive(true)
                .gender(Gender.MALE)
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.ADMIN))
                .build();
        entityManager.persist(admin);

        // 2. Organizer User
        User organizer = User.builder()
                .id("22222222-2222-2222-2222-222222222222")
                .keycloakId("kc-organizer-001")
                .email("organizer@haloticket.vn")
                .fullName("Trần Công Minh (Halo Entertainment)")
                .phoneNumber("0912345678")
                .avatarUrl("https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200")
                .isActive(true)
                .gender(Gender.MALE)
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.ORGANIZER, Role.CUSTOMER))
                .build();

        EkycProfile orgEkyc = EkycProfile.builder()
                .id("ekyc-org-001")
                .user(organizer)
                .documentType(EkycDocumentType.BUSINESS_LICENSE)
                .documentNumber("0312345678")
                .frontImageUrl("https://example.com/ekyc/license_front.jpg")
                .backImageUrl("https://example.com/ekyc/license_back.jpg")
                .bankName("MBBank")
                .bankAccountName("CONG TY HALO ENTERTAINMENT")
                .bankAccountNumber("999988887777")
                .status(EkycVerificationStatus.APPROVED)
                .approvedAt(LocalDateTime.now().minusDays(30))
                .build();

        organizer.setEkycProfile(orgEkyc);
        entityManager.persist(organizer);

        // 3. Staff User
        User staff = User.builder()
                .id("33333333-3333-3333-3333-333333333333")
                .keycloakId("kc-staff-001")
                .email("staff@haloticket.vn")
                .fullName("Nguyễn Văn Soát Vé")
                .phoneNumber("0987654321")
                .avatarUrl("https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200")
                .isActive(true)
                .gender(Gender.MALE)
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.STAFF))
                .build();
        entityManager.persist(staff);

        // 4. Customer User
        User customer = User.builder()
                .id("44444444-4444-4444-4444-444444444444")
                .keycloakId("kc-customer-001")
                .email("customer@haloticket.vn")
                .fullName("Lê Thị Mua Vé")
                .phoneNumber("0933445566")
                .avatarUrl("https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200")
                .isActive(true)
                .gender(Gender.FEMALE)
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.CUSTOMER))
                .build();

        EkycProfile customerEkyc = EkycProfile.builder()
                .id("ekyc-cust-001")
                .user(customer)
                .documentType(EkycDocumentType.ID_CARD)
                .documentNumber("079200001234")
                .frontImageUrl("https://example.com/ekyc/cccd_front.jpg")
                .backImageUrl("https://example.com/ekyc/cccd_back.jpg")
                .bankName("Vietcombank")
                .bankAccountName("LE THI MUA VE")
                .bankAccountNumber("1012345678")
                .status(EkycVerificationStatus.APPROVED)
                .approvedAt(LocalDateTime.now().minusDays(15))
                .build();

        customer.setEkycProfile(customerEkyc);
        entityManager.persist(customer);

        log.info("User Service sample data initialized successfully! Created 4 users.");
    }
}
