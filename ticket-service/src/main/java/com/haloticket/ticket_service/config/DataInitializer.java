package com.haloticket.ticket_service.config;

import com.haloticket.ticket_service.entity.*;
import com.haloticket.ticket_service.enums.*;
import com.haloticket.ticket_service.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Nạp dữ liệu mẫu (loại vé, sơ đồ ghế, vé, lịch sử check-in) khi khởi động nếu bảng loại vé còn trống. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final TicketTypeRepository ticketTypeRepository;

    @PersistenceContext
    private final EntityManager entityManager;

    /** Nếu đã có loại vé thì bỏ qua; ngược lại tạo toàn bộ dữ liệu mẫu trong một transaction. */
    @Override
    @Transactional
    public void run(String... args) {
        if (ticketTypeRepository.count() > 0) {
            log.info("Ticket database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for Ticket Service...");

        // 1. Loại vé cho sự kiện 001
        TicketType vipType = TicketType.builder()
                .eventId("event-001")
                .name("Vé VIP Fan Zone")
                .price(1500000.0)
                .totalQuantity(100)
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
        entityManager.persist(vipType);

        TicketType stdType = TicketType.builder()
                .eventId("event-001")
                .name("Vé Khán Đài A (Standard)")
                .price(800000.0)
                .totalQuantity(200)
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
        entityManager.persist(stdType);

        TicketType ecoType = TicketType.builder()
                .eventId("event-001")
                .name("Vé Khán Đài B (Economy)")
                .price(500000.0)
                .totalQuantity(300)
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
        entityManager.persist(ecoType);

        // Loại vé cho sự kiện 002 (thể thao)
        TicketType marathonType = TicketType.builder()
                .eventId("event-002")
                .name("Vé Tham Gia Marathon 21KM")
                .price(450000.0)
                .totalQuantity(500)
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
        entityManager.persist(marathonType);

        // 2. Sơ đồ ghế
        SeatLayout layout = SeatLayout.builder()
                .eventId("event-001")
                .svgData("<svg viewBox='0 0 800 600'><rect width='800' height='100' fill='#333'/><text x='350' y='60' fill='#fff'>SÂN SÂN KHẤU CHÍNH</text></svg>")
                .status(SeatLayoutStatus.PUBLISHED)
                .build();
        entityManager.persist(layout);

        // 3. Khu vực ghế
        SeatZone vipZone = SeatZone.builder()
                .seatLayout(layout)
                .name("Khu VIP (Sát sân khấu)")
                .colorCode("#FFD700")
                .capacity(20)
                .build();
        entityManager.persist(vipZone);

        SeatZone stdZone = SeatZone.builder()
                .seatLayout(layout)
                .name("Khu Khán Đài A")
                .colorCode("#1E90FF")
                .capacity(40)
                .build();
        entityManager.persist(stdZone);

        // 4. Hàng ghế
        SeatRow rowA = SeatRow.builder()
                .seatZone(vipZone)
                .label("Hàng A")
                .build();
        entityManager.persist(rowA);

        SeatRow rowB = SeatRow.builder()
                .seatZone(vipZone)
                .label("Hàng B")
                .build();
        entityManager.persist(rowB);

        SeatRow rowC = SeatRow.builder()
                .seatZone(stdZone)
                .label("Hàng C")
                .build();
        entityManager.persist(rowC);

        // 5. Ghế
        Seat seatA1 = Seat.builder()
                .seatRow(rowA)
                .seatNumber("A1")
                .xCoordinate(100.0)
                .yCoordinate(200.0)
                .seatType(SeatType.VIP)
                .seatStatus(SeatStatus.SOLD)
                .build();
        entityManager.persist(seatA1);

        Seat seatA2 = Seat.builder()
                .seatRow(rowA)
                .seatNumber("A2")
                .xCoordinate(140.0)
                .yCoordinate(200.0)
                .seatType(SeatType.VIP)
                .seatStatus(SeatStatus.SOLD)
                .build();
        entityManager.persist(seatA2);

        Seat seatA3 = Seat.builder()
                .seatRow(rowA)
                .seatNumber("A3")
                .xCoordinate(180.0)
                .yCoordinate(200.0)
                .seatType(SeatType.VIP)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
        entityManager.persist(seatA3);

        Seat seatC1 = Seat.builder()
                .seatRow(rowC)
                .seatNumber("C1")
                .xCoordinate(100.0)
                .yCoordinate(350.0)
                .seatType(SeatType.REGULAR)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
        entityManager.persist(seatC1);

        // 6. Vé (đã phát hành cho người dùng khách hàng)
        Ticket ticket1 = Ticket.builder()
                .ticketType(vipType)
                .seat(seatA1)
                .eventId("event-001")
                .bookingId("booking-001")
                .ownerId("44444444-4444-4444-4444-444444444444") // người dùng khách hàng
                .qrCodeHash("QR_HALO_001_VIP_A1")
                .qrVersion(1)
                .status(TicketStatus.VALID)
                .issuedAt(LocalDateTime.now().minusDays(2))
                .build();
        entityManager.persist(ticket1);

        Ticket ticket2 = Ticket.builder()
                .ticketType(vipType)
                .seat(seatA2)
                .eventId("event-001")
                .bookingId("booking-001")
                .ownerId("44444444-4444-4444-4444-444444444444") // người dùng khách hàng
                .qrCodeHash("QR_HALO_002_VIP_A2")
                .qrVersion(1)
                .status(TicketStatus.USED)
                .issuedAt(LocalDateTime.now().minusDays(2))
                .usedAt(LocalDateTime.now().minusHours(1))
                .build();
        entityManager.persist(ticket2);

        // 7. Lượt check-in và lần quét thử
        Checkin checkin = Checkin.builder()
                .ticket(ticket2)
                .eventId("event-001")
                .staffAssignmentId("staff-assign-01")
                .status(CheckInStatus.VALID)
                .checkedInAt(LocalDateTime.now().minusHours(1))
                .build();
        entityManager.persist(checkin);

        CheckInAttempt attempt = CheckInAttempt.builder()
                .ticket(ticket2)
                .staffAssignmentId("staff-assign-01")
                .deviceInfo("Mobile Scanner App 01 - Gate A")
                .result(CheckInAttemptResult.SUCCESS)
                .attemptedAt(LocalDateTime.now().minusHours(1))
                .build();
        entityManager.persist(attempt);

        log.info("Ticket Service sample data initialized successfully! Created ticket types, seat layout, zones, rows, seats, tickets, and check-in history.");
    }
}
