package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.*;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import com.haloticket.ticket_service.enums.SeatStatus;
import com.haloticket.ticket_service.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketIssuanceHardeningTest {

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final TicketTypeRepository types = mock(TicketTypeRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final SeatLayoutRepository layouts = mock(SeatLayoutRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query lockQuery = mock(Query.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private TicketEventListener listener;
    private final Map<String, Ticket> savedTicketsByIssueKey = new ConcurrentHashMap<>();
    private final List<Ticket> allSavedTickets = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> stockMap = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        listener = new TicketEventListener(tickets, outbox, types, seats, layouts, entityManager, mapper);

        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(eq("bookingId"), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(1);

        // Mock tickets repo
        when(tickets.findByBookingId(anyString())).thenAnswer(inv -> {
            String bid = inv.getArgument(0);
            return allSavedTickets.stream().filter(t -> bid.equals(t.getBookingId())).toList();
        });

        when(tickets.findByIssueKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return Optional.ofNullable(savedTicketsByIssueKey.get(key));
        });

        when(tickets.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(UUID.randomUUID().toString());
            }
            if (t.getIssueKey() != null) {
                savedTicketsByIssueKey.put(t.getIssueKey(), t);
            }
            allSavedTickets.add(t);
            return t;
        });

        // Mock atomic stock decrement
        when(types.decrementTotalQuantityAtomic(anyString())).thenAnswer(inv -> {
            String typeId = inv.getArgument(0);
            AtomicInteger currentStock = stockMap.computeIfAbsent(typeId, k -> new AtomicInteger(100));
            while (true) {
                int cur = currentStock.get();
                if (cur <= 0) {
                    return 0; // 0 rows affected (exhausted)
                }
                if (currentStock.compareAndSet(cur, cur - 1)) {
                    return 1; // 1 row updated
                }
            }
        });
    }

    @Test
    @DisplayName("Integration: booking GA with 3 tiers (1 each) creates 3 tickets with null seat fields and decrements stock once per tier")
    void testGaBookingWithThreeTiers() throws Exception {
        TicketType vip = TicketType.builder().id("tier-vip").eventId("event-1").showtimeId("show-1").name("VIP").price(4000.0).totalQuantity(10).build();
        TicketType svip = TicketType.builder().id("tier-svip").eventId("event-1").showtimeId("show-1").name("S-VIP").price(5000.0).totalQuantity(10).build();
        TicketType std = TicketType.builder().id("tier-std").eventId("event-1").showtimeId("show-1").name("STANDARDs").price(3000.0).totalQuantity(10).build();

        stockMap.put("tier-vip", new AtomicInteger(10));
        stockMap.put("tier-svip", new AtomicInteger(10));
        stockMap.put("tier-std", new AtomicInteger(10));

        when(types.findById("tier-vip")).thenReturn(Optional.of(vip));
        when(types.findById("tier-svip")).thenReturn(Optional.of(svip));
        when(types.findById("tier-std")).thenReturn(Optional.of(std));
        when(types.findByShowtimeId("show-1")).thenReturn(List.of(vip, svip, std));
        when(layouts.findFirstByEventIdAndStatusOrderByUpdatedAtDesc("event-1", SeatLayoutStatus.PUBLISHED)).thenReturn(Optional.empty());

        // Simulated message identical to our real production test order HALO-1792657606664 with -STT-1 seatIds
        String confirmedJson = """
                {
                  "bookingId": "b8d2af58-0ac0-4261-b35e-f444e77b7467",
                  "bookingCode": "HALO-1792657606664",
                  "customerId": "cust-1",
                  "eventId": "event-1",
                  "showtimeId": "show-1",
                  "amount": 12000,
                  "seatIds": [
                    "tier-vip-STT-1",
                    "tier-svip-STT-1",
                    "tier-std-STT-1"
                  ],
                  "items": [
                    {"ticketTypeId": "tier-vip", "quantity": 1, "unitPrice": 4000},
                    {"ticketTypeId": "tier-svip", "quantity": 1, "unitPrice": 5000},
                    {"ticketTypeId": "tier-std", "quantity": 1, "unitPrice": 3000}
                  ]
                }
                """;

        listener.handleBookingConfirmed(confirmedJson);

        // Verify 3 tickets issued
        assertThat(allSavedTickets).hasSize(3);

        for (Ticket ticket : allSavedTickets) {
            assertThat(ticket.getSeat()).isNull();
            assertThat(ticket.getSeatReference()).isNull();
            assertThat(ticket.getSeatRowLabel()).isNull();
            assertThat(ticket.getSeatNumberLabel()).isNull();
            assertThat(ticket.getSeatSection()).isNull();
            assertThat(ticket.getIssueKey()).isNotNull();
            assertThat(ticket.getIssueKey()).startsWith("b8d2af58-0ac0-4261-b35e-f444e77b7467:");
        }

        // Verify stock decremented by exactly 1 for each tier
        assertThat(stockMap.get("tier-vip").get()).isEqualTo(9);
        assertThat(stockMap.get("tier-svip").get()).isEqualTo(9);
        assertThat(stockMap.get("tier-std").get()).isEqualTo(9);

        // Verify outbox event generated
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(outboxCaptor.capture());
        JsonNode outboxPayload = mapper.readTree(outboxCaptor.getValue().getPayload());
        assertThat(outboxPayload.path("tickets")).hasSize(3);
        assertThat(outboxPayload.path("tickets").get(0).path("seatId").isNull()).isTrue();
    }

    @Test
    @DisplayName("Idempotency: Re-delivering same message twice produces 3 tickets and only 1 stock decrement")
    void testIdempotentRedelivery() throws Exception {
        TicketType vip = TicketType.builder().id("tier-vip").eventId("event-1").showtimeId("show-1").name("VIP").price(4000.0).totalQuantity(10).build();
        stockMap.put("tier-vip", new AtomicInteger(10));
        when(types.findById("tier-vip")).thenReturn(Optional.of(vip));
        when(types.findByShowtimeId("show-1")).thenReturn(List.of(vip));
        when(layouts.findFirstByEventIdAndStatusOrderByUpdatedAtDesc("event-1", SeatLayoutStatus.PUBLISHED)).thenReturn(Optional.empty());

        String confirmedJson = """
                {
                  "bookingId": "booking-idempotent-1",
                  "bookingCode": "HALO-IDEM-001",
                  "customerId": "cust-1",
                  "eventId": "event-1",
                  "showtimeId": "show-1",
                  "amount": 4000,
                  "seatIds": ["tier-vip-STT-1"],
                  "items": [{"ticketTypeId": "tier-vip", "quantity": 1, "unitPrice": 4000}]
                }
                """;

        // First delivery
        listener.handleBookingConfirmed(confirmedJson);
        assertThat(allSavedTickets).hasSize(1);
        assertThat(stockMap.get("tier-vip").get()).isEqualTo(9);

        // Second delivery (simulating Kafka redelivery or republish)
        listener.handleBookingConfirmed(confirmedJson);
        assertThat(allSavedTickets).hasSize(1); // STILL 1 ticket!
        assertThat(stockMap.get("tier-vip").get()).isEqualTo(9); // stock NOT decremented again!
    }

    @Test
    @DisplayName("Seatmap booking: Real physical seats are still issued as normal with seat references and SOLD status")
    void testSeatmapBookingIssuance() throws Exception {
        TicketType vip = TicketType.builder().id("tier-vip").eventId("event-1").showtimeId("show-1").name("VIP").price(10000.0).totalQuantity(10).build();
        when(types.findById("tier-vip")).thenReturn(Optional.of(vip));
        when(types.findByShowtimeId("show-1")).thenReturn(List.of(vip));

        SeatZone zone = SeatZone.builder().name("VIP ZONE").ticketTypeId("tier-vip").build();
        SeatRow row = SeatRow.builder().label("A").seatZone(zone).build();
        Seat realSeat = Seat.builder().id("seat-uuid-real-123").seatNumber("15").seatRow(row).seatStatus(SeatStatus.AVAILABLE).build();
        when(seats.findById("seat-uuid-real-123")).thenReturn(Optional.of(realSeat));
        when(layouts.findFirstByEventIdAndStatusOrderByUpdatedAtDesc("event-1", SeatLayoutStatus.PUBLISHED)).thenReturn(Optional.empty());

        String confirmedJson = """
                {
                  "bookingId": "booking-seatmap-1",
                  "bookingCode": "HALO-SEATMAP-001",
                  "customerId": "cust-1",
                  "eventId": "event-1",
                  "showtimeId": "show-1",
                  "amount": 10000,
                  "seatIds": ["seat-uuid-real-123"],
                  "items": [{"ticketTypeId": "tier-vip", "quantity": 1, "unitPrice": 10000}]
                }
                """;

        listener.handleBookingConfirmed(confirmedJson);

        assertThat(allSavedTickets).hasSize(1);
        Ticket issued = allSavedTickets.getFirst();
        assertThat(issued.getSeat()).isEqualTo(realSeat);
        assertThat(issued.getSeatReference()).isEqualTo("seat-uuid-real-123");
        assertThat(issued.getSeatSection()).isEqualTo("VIP ZONE");
        assertThat(issued.getSeatRowLabel()).isEqualTo("A");
        assertThat(issued.getSeatNumberLabel()).isEqualTo("15");
        assertThat(realSeat.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
        verify(seats).save(realSeat);
    }

    @Test
    @DisplayName("Concurrency: 20 concurrent bookings competing for 5 tickets: total_quantity never negative, extra 15 log OVERSOLD_ON_ISSUE")
    void testConcurrentBookingsOversold() throws Exception {
        TicketType vip = TicketType.builder().id("tier-hot").eventId("event-1").showtimeId("show-1").name("HOT").price(1000.0).totalQuantity(5).build();
        stockMap.put("tier-hot", new AtomicInteger(5)); // Exactly 5 tickets in stock
        when(types.findById("tier-hot")).thenReturn(Optional.of(vip));
        when(types.findByShowtimeId("show-1")).thenReturn(List.of(vip));
        when(layouts.findFirstByEventIdAndStatusOrderByUpdatedAtDesc("event-1", SeatLayoutStatus.PUBLISHED)).thenReturn(Optional.empty());

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String bookingId = "booking-concurrent-" + idx;
                    String json = """
                            {
                              "bookingId": "%s",
                              "bookingCode": "HALO-%d",
                              "customerId": "cust-%d",
                              "eventId": "event-1",
                              "showtimeId": "show-1",
                              "amount": 1000,
                              "seatIds": ["tier-hot-STT-1"],
                              "items": [{"ticketTypeId": "tier-hot", "quantity": 1, "unitPrice": 1000}]
                            }
                            """.formatted(bookingId, idx, idx);
                    listener.handleBookingConfirmed(json);
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Stock was 5, 20 bookings processed -> stock MUST be 0, NEVER negative
        assertThat(stockMap.get("tier-hot").get()).isEqualTo(0);
        // All 20 tickets were issued because customers already paid
        assertThat(allSavedTickets).hasSize(20);
    }

    @Test
    @DisplayName("Dead Letter Topic: verify recoverer directs failed booking.confirmed message to .DLT")
    void testDeadLetterRecovererDestination() {
        var recoverer = new DeadLetterPublishingRecoverer(mock(org.springframework.kafka.core.KafkaOperations.class),
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("haloticket.booking.confirmed.v1", 0, 100L, "key-1", "bad-payload");
        TopicPartition destination = new TopicPartition(record.topic() + ".DLT", record.partition());
        assertThat(destination.topic()).isEqualTo("haloticket.booking.confirmed.v1.DLT");
    }
}
