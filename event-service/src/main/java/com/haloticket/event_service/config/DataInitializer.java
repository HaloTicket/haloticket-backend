package com.haloticket.event_service.config;

import com.haloticket.event_service.entity.*;
import com.haloticket.event_service.enums.*;
import com.haloticket.event_service.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final EventRepository eventRepository;

    @PersistenceContext
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        if (eventRepository.existsById("event-001")) {
            log.info("Event database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for Event Service...");

        // 1. Categories
        EventCategory musicCat = EventCategory.builder()
                .id("cat-001")
                .name("Âm Nhạc & Concert")
                .description("Hòa nhạc, live show âm nhạc kịch tính, lễ hội âm nhạc đỉnh cao.")
                .status(CategoryStatus.ACTIVE)
                .build();
        entityManager.persist(musicCat);

        EventCategory sportsCat = EventCategory.builder()
                .id("cat-002")
                .name("Thể Thao & Giải Đấu")
                .description("Giải chạy marathon, bóng đá, esports và sự kiện thể thao sôi động.")
                .status(CategoryStatus.ACTIVE)
                .build();
        entityManager.persist(sportsCat);

        EventCategory theaterCat = EventCategory.builder()
                .id("cat-003")
                .name("Sân Khấu & Nghệ Thuật")
                .description("Kịch nói, nhạc kịch, hài kịch và trình diễn nghệ thuật.")
                .status(CategoryStatus.ACTIVE)
                .build();
        entityManager.persist(theaterCat);

        EventCategory techCat = EventCategory.builder()
                .id("cat-004")
                .name("Hội Thảo & Công Nghệ")
                .description("Tech summit, hội thảo AI, khởi nghiệp và triển lãm công nghệ.")
                .status(CategoryStatus.ACTIVE)
                .build();
        entityManager.persist(techCat);

        // 2. Organizer Profile
        EventOrganizer organizer = EventOrganizer.builder()
                .id("org-001")
                .userId("22222222-2222-2222-2222-222222222222") // Links to Organizer User
                .description("Halo Entertainment Inc. - Đơn vị tổ chức sự kiện âm nhạc và văn hóa hàng đầu.")
                .role(EventOrganizerRole.OWNER)
                .status(EventOrganizerStatus.ACTIVE)
                .build();
        entityManager.persist(organizer);

        // 3. Events
        Event musicEvent = Event.builder()
                .id("event-001")
                .title("Đêm Nhạc Halo Concert 2026: Vũ Điệu Âm Thanh")
                .description("Đêm nhạc bùng nổ quy tụ những ca sĩ hàng đầu Việt Nam với công nghệ sân khấu và hệ thống ánh sáng 3D hoành tráng.")
                .bannerUrl("https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=1200")
                .thumbnailUrl("https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=400")
                .locationName("Sân vận động Quân khu 7")
                .address("202 Hoàng Văn Thụ, Phường 9, Quận Phú Nhuận, TP.Hồ Chí Minh")
                .eventStatus(EventStatus.LIVE)
                .eventApprovalStatus(EventApprovalStatus.APPROVED)
                .startTime(LocalDateTime.of(2026, 10, 15, 19, 0))
                .endTime(LocalDateTime.of(2026, 10, 15, 22, 30))
                .saleStartTime(LocalDateTime.of(2026, 8, 1, 9, 0))
                .saleEndTime(LocalDateTime.of(2026, 10, 14, 23, 59))
                .category(musicCat)
                .organizers(List.of(organizer))
                .build();
        entityManager.persist(musicEvent);

        Event sportsEvent = Event.builder()
                .id("event-002")
                .title("Giải Chạy Halo Marathon TP.HCM 2026")
                .description("Giải chạy việt dã thường niên mở rộng dành cho các vận động viên chuyên nghiệp và phong trào thử thách bản thân.")
                .bannerUrl("https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?w=1200")
                .thumbnailUrl("https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?w=400")
                .locationName("Công viên Văn hóa Đầm Sen")
                .address("3 Hòa Bình, Phường 3, Quận 11, TP.Hồ Chí Minh")
                .eventStatus(EventStatus.LIVE)
                .eventApprovalStatus(EventApprovalStatus.APPROVED)
                .startTime(LocalDateTime.of(2026, 11, 20, 5, 0))
                .endTime(LocalDateTime.of(2026, 11, 20, 11, 0))
                .saleStartTime(LocalDateTime.of(2026, 8, 10, 9, 0))
                .saleEndTime(LocalDateTime.of(2026, 11, 19, 23, 59))
                .category(sportsCat)
                .organizers(List.of(organizer))
                .build();
        entityManager.persist(sportsEvent);

        Event techEvent = Event.builder()
                .id("event-003")
                .title("Hội Thảo Công Nghệ Halo AI Summit 2026")
                .description("Hội thảo công nghệ AI & Cloud Microservices quy tụ hơn 50 diễn giả quốc tế và các chuyên gia phần mềm hàng đầu.")
                .bannerUrl("https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=1200")
                .thumbnailUrl("https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=400")
                .locationName("Trung tâm Hội nghị GEM Center")
                .address("8 Nguyễn Bỉnh Khiêm, Đa Kao, Quận 1, TP.Hồ Chí Minh")
                .eventStatus(EventStatus.LIVE)
                .eventApprovalStatus(EventApprovalStatus.APPROVED)
                .startTime(LocalDateTime.of(2026, 12, 5, 8, 30))
                .endTime(LocalDateTime.of(2026, 12, 5, 17, 0))
                .saleStartTime(LocalDateTime.of(2026, 8, 15, 9, 0))
                .saleEndTime(LocalDateTime.of(2026, 12, 4, 23, 59))
                .category(techCat)
                .organizers(List.of(organizer))
                .build();
        entityManager.persist(techEvent);

        // 4. Staff Assignment
        StaffAssignment staffAssignment = StaffAssignment.builder()
                .id("staff-assign-01")
                .event(musicEvent)
                .userId("33333333-3333-3333-3333-333333333333") // Links to Staff User
                .status(StaffAssignmentStatus.ACTIVE)
                .assignedAt(LocalDateTime.now())
                .build();
        entityManager.persist(staffAssignment);

        // 5. Vouchers
        Voucher vipVoucher = Voucher.builder()
                .id("voucher-01")
                .event(musicEvent)
                .code("HALOVIP2026")
                .name("Ưu đãi 10% vé VIP Concert")
                .description("Giảm 10% giá trị đơn hàng cho vé VIP, tối đa 200.000 VNĐ.")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(10.0)
                .maxDiscountAmount(200000.0)
                .minOrderAmount(500000.0)
                .usageLimit(100)
                .usedCount(5)
                .startAt(LocalDateTime.of(2026, 8, 1, 0, 0))
                .endAt(LocalDateTime.of(2026, 10, 14, 23, 59))
                .status(VoucherStatus.ACTIVE)
                .build();
        entityManager.persist(vipVoucher);

        Voucher fixedVoucher = Voucher.builder()
                .id("voucher-02")
                .event(musicEvent)
                .code("HALO50K")
                .name("Giảm trực tiếp 50.000 VNĐ")
                .description("Giảm 50.000 VNĐ cho đơn hàng từ 300.000 VNĐ.")
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(50000.0)
                .minOrderAmount(300000.0)
                .usageLimit(50)
                .usedCount(2)
                .startAt(LocalDateTime.of(2026, 8, 1, 0, 0))
                .endAt(LocalDateTime.of(2026, 10, 14, 23, 59))
                .status(VoucherStatus.ACTIVE)
                .build();
        entityManager.persist(fixedVoucher);

        log.info("Event Service sample data initialized successfully! Created categories, organizer, 3 events, staff assignment, and vouchers.");
    }
}
