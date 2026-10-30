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
        if (eventRepository.count() > 0) {
            log.info("Event database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for Event Service...");

        // 0. Preset Venues
        Venue myDinh = Venue.builder()
                .name("Sân vận động Quốc gia Mỹ Đình")
                .address("Đường Lê Đức Thọ, Phường Mỹ Đình 1, Quận Nam Từ Liêm")
                .city("Hà Nội")
                .ward("Phường Mỹ Đình 1")
                .capacity(40000)
                .type("outdoor")
                .verified(true)
                .eventsHosted(25)
                .build();
        entityManager.merge(myDinh);

        Venue phuTho = Venue.builder()
                .name("Nhà thi đấu Phú Thọ")
                .address("1 Lý Thường Kiệt, Phường 15, Quận 11")
                .city("TP.Hồ Chí Minh")
                .ward("Phường 15")
                .capacity(5000)
                .type("indoor")
                .verified(true)
                .eventsHosted(18)
                .build();
        entityManager.merge(phuTho);

        Venue ncc = Venue.builder()
                .name("Trung tâm Hội nghị Quốc gia")
                .address("57 Phạm Hùng, Phường Mễ Trì, Quận Nam Từ Liêm")
                .city("Hà Nội")
                .ward("Phường Mễ Trì")
                .capacity(3800)
                .type("indoor")
                .verified(true)
                .eventsHosted(30)
                .build();
        entityManager.merge(ncc);

        Venue hoaBinh = Venue.builder()
                .name("Nhà hát Hòa Bình")
                .address("240 3 Tháng 2, Phường 12, Quận 10")
                .city("TP.Hồ Chí Minh")
                .ward("Phường 12")
                .capacity(2500)
                .type("indoor")
                .verified(true)
                .eventsHosted(12)
                .build();
        entityManager.merge(hoaBinh);

        Venue quanNguai = Venue.builder()
                .name("Cung Thể thao Quần Ngựa")
                .address("30 Văn Cao, Phường Liễu Giai, Quận Ba Đình")
                .city("Hà Nội")
                .ward("Phường Liễu Giai")
                .capacity(5500)
                .type("hybrid")
                .verified(true)
                .eventsHosted(10)
                .build();
        entityManager.merge(quanNguai);

        Venue gemCenter = Venue.builder()
                .name("Trung tâm Hội nghị GEM Center")
                .address("8 Nguyễn Bỉnh Khiêm, Đa Kao, Quận 1")
                .city("TP.Hồ Chí Minh")
                .ward("Phường Đa Kao")
                .capacity(1200)
                .type("indoor")
                .verified(true)
                .eventsHosted(45)
                .build();
        entityManager.merge(gemCenter);

        // 1. Categories
        EventCategory musicCat = EventCategory.builder()
                .id("cat-001")
                .name("Âm Nhạc & Concert")
                .description("Hòa nhạc, live show âm nhạc kịch tính, lễ hội âm nhạc đỉnh cao.")
                .status(CategoryStatus.ACTIVE)
                .build();
        entityManager.merge(musicCat);

        EventCategory sportsCat = EventCategory.builder()
                .id("cat-002")
                .name("Thể Thao & Giải Đấu")
                .description("Giải chạy marathon, bóng đá, esports và sự kiện thể thao sôi động.")
                .status(CategoryStatus.ACTIVE)
                .build();
        entityManager.merge(sportsCat);

        EventCategory theaterCat = EventCategory.builder()
                .id("cat-003")
                .name("Sân Khấu & Nghệ Thuật")
                .description("Kịch nói, nhạc kịch, hài kịch và trình diễn nghệ thuật.")
                .status(CategoryStatus.ACTIVE)
                .build();
        entityManager.merge(theaterCat);

        EventCategory techCat = EventCategory.builder()
                .id("cat-004")
                .name("Hội Thảo & Công Nghệ")
                .description("Tech summit, hội thảo AI, khởi nghiệp và triển lãm công nghệ.")
                .status(CategoryStatus.ACTIVE)
                .build();
        entityManager.merge(techCat);

        // 2. Organizer Profile
        EventOrganizer organizer = EventOrganizer.builder()
                .id("org-001")
                .userId("22222222-2222-2222-2222-222222222222") // Links to Organizer User
                .description("Halo Entertainment Inc. - Đơn vị tổ chức sự kiện âm nhạc và văn hóa hàng đầu.")
                .role(EventOrganizerRole.OWNER)
                .status(EventOrganizerStatus.ACTIVE)
                .build();
        entityManager.merge(organizer);

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
        entityManager.merge(withOwner(musicEvent, organizer, "sa-seed-001"));

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
        entityManager.merge(withOwner(sportsEvent, organizer, "sa-seed-002"));

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
        entityManager.merge(withOwner(techEvent, organizer, "sa-seed-003"));

        log.info("Event Service sample data initialized successfully!");
    }

    /** The seeded event's owner: an OWNER ACTIVE staff assignment for the seed organizer's user (what authorization reads). */
    private static Event withOwner(Event event, EventOrganizer organizer, String assignmentId) {
        event.setStaffAssignments(new java.util.ArrayList<>(List.of(StaffAssignment.builder()
                .id(assignmentId)
                .event(event)
                .userId(organizer.getUserId())
                .name(organizer.getName())
                .role(StaffRole.OWNER)
                .status(StaffAssignmentStatus.ACTIVE)
                .assignedAt(LocalDateTime.now())
                .build())));
        return event;
    }
}
