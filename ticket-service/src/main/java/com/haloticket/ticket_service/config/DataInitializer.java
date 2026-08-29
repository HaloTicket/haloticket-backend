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

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final TicketTypeRepository ticketTypeRepository;

    @PersistenceContext
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        if (ticketTypeRepository.existsById("tt-001")) {
            log.info("Ticket database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for Ticket Service...");

        // 1. Ticket Types for Event 001
        TicketType vipType = TicketType.builder()
                .id("tt-001")
                .eventId("event-001")
                .name("Vé VIP Fan Zone")
                .price(1500000.0)
                .totalQuantity(100)
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
        entityManager.persist(vipType);

        TicketType stdType = TicketType.builder()
                .id("tt-002")
                .eventId("event-001")
                .name("Vé Khán Đài A (Standard)")
                .price(800000.0)
                .totalQuantity(200)
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
        entityManager.persist(stdType);

        TicketType ecoType = TicketType.builder()
                .id("tt-003")
                .eventId("event-001")
                .name("Vé Khán Đài B (Economy)")
                .price(500000.0)
                .totalQuantity(300)
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
        entityManager.persist(ecoType);

        // Ticket Types for Event 002 (Sports)
        TicketType marathonType = TicketType.builder()
                .id("tt-004")
                .eventId("event-002")
                .name("Vé Tham Gia Marathon 21KM")
                .price(450000.0)
                .totalQuantity(500)
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
        entityManager.persist(marathonType);

        // 2. Seat Layout
        SeatLayout layout = SeatLayout.builder()
                .id("layout-001")
                .eventId("event-001")
                .svgData("<svg viewBox='0 0 800 600'><rect width='800' height='100' fill='#333'/><text x='350' y='60' fill='#fff'>SÂN SÂN KHẤU CHÍNH</text></svg>")
                .status(SeatLayoutStatus.PUBLISHED)
                .build();
        entityManager.persist(layout);

        // 3. Seat Zones
        SeatZone vipZone = SeatZone.builder()
                .id("zone-vip")
                .seatLayout(layout)
                .name("Khu VIP (Sát sân khấu)")
                .colorCode("#FFD700")
                .capacity(20)
                .build();
        entityManager.persist(vipZone);

        SeatZone stdZone = SeatZone.builder()
                .id("zone-std")
                .seatLayout(layout)
                .name("Khu Khán Đài A")
                .colorCode("#1E90FF")
                .capacity(40)
                .build();
        entityManager.persist(stdZone);

        // 4. Seat Rows
        SeatRow rowA = SeatRow.builder()
                .id("row-a")
                .seatZone(vipZone)
                .label("Hàng A")
                .build();
        entityManager.persist(rowA);

        SeatRow rowB = SeatRow.builder()
                .id("row-b")
                .seatZone(vipZone)
                .label("Hàng B")
                .build();
        entityManager.persist(rowB);

        SeatRow rowC = SeatRow.builder()
                .id("row-c")
                .seatZone(stdZone)
                .label("Hàng C")
                .build();
        entityManager.persist(rowC);

        // 5. Seats
        Seat seatA1 = Seat.builder()
                .id("seat-a1")
                .seatRow(rowA)
                .seatNumber("A1")
                .xCoordinate(100.0)
                .yCoordinate(200.0)
                .seatType(SeatType.VIP)
                .seatStatus(SeatStatus.SOLD)
                .build();
        entityManager.persist(seatA1);

        Seat seatA2 = Seat.builder()
                .id("seat-a2")
                .seatRow(rowA)
                .seatNumber("A2")
                .xCoordinate(140.0)
                .yCoordinate(200.0)
                .seatType(SeatType.VIP)
                .seatStatus(SeatStatus.SOLD)
                .build();
        entityManager.persist(seatA2);

        Seat seatA3 = Seat.builder()
                .id("seat-a3")
                .seatRow(rowA)
                .seatNumber("A3")
                .xCoordinate(180.0)
                .yCoordinate(200.0)
                .seatType(SeatType.VIP)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
        entityManager.persist(seatA3);

        Seat seatC1 = Seat.builder()
                .id("seat-c1")
                .seatRow(rowC)
                .seatNumber("C1")
                .xCoordinate(100.0)
                .yCoordinate(350.0)
                .seatType(SeatType.REGULAR)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
        entityManager.persist(seatC1);

        // 6. Tickets (Issued for Customer User)
        Ticket ticket1 = Ticket.builder()
                .id("ticket-001")
                .ticketType(vipType)
                .seat(seatA1)
                .eventId("event-001")
                .bookingId("booking-001")
                .ownerId("44444444-4444-4444-4444-444444444444") // Customer User
                .qrCodeHash("QR_HALO_001_VIP_A1")
                .qrVersion(1)
                .status(TicketStatus.VALID)
                .issuedAt(LocalDateTime.now().minusDays(2))
                .build();
        entityManager.persist(ticket1);

        Ticket ticket2 = Ticket.builder()
                .id("ticket-002")
                .ticketType(vipType)
                .seat(seatA2)
                .eventId("event-001")
                .bookingId("booking-001")
                .ownerId("44444444-4444-4444-4444-444444444444") // Customer User
                .qrCodeHash("QR_HALO_002_VIP_A2")
                .qrVersion(1)
                .status(TicketStatus.USED)
                .issuedAt(LocalDateTime.now().minusDays(2))
                .usedAt(LocalDateTime.now().minusHours(1))
                .build();
        entityManager.persist(ticket2);

        // 7. Checkins & CheckInAttempt
        Checkin checkin = Checkin.builder()
                .id("checkin-001")
                .ticket(ticket2)
                .eventId("event-001")
                .staffAssignmentId("staff-assign-01")
                .status(CheckInStatus.VALID)
                .checkedInAt(LocalDateTime.now().minusHours(1))
                .build();
        entityManager.persist(checkin);

        CheckInAttempt attempt = CheckInAttempt.builder()
                .id("attempt-001")
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
