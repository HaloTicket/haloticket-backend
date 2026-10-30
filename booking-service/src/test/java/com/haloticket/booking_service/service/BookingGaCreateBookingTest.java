package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.TicketServiceClient;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.service.impl.BookingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** createBooking with general-admission stock holds on and off; seat flows must not change. */
class BookingGaCreateBookingTest {

    private static final String GA = "11111111-1111-1111-1111-111111111111";
    private static final String VIP = "22222222-2222-2222-2222-222222222222";
    private static final String TOKEN = "tok";

    private BookingRepository bookings;
    private SeatReservationRepository seatReservations;
    private OutboxEventRepository outbox;
    private RedissonClient redisson;
    private RLock lock;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private TicketStockClient stockClient;
    private GaReservationProperties properties;
    private BookingServiceImpl service;
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        bookings = mock(BookingRepository.class);
        seatReservations = mock(SeatReservationRepository.class);
        outbox = mock(OutboxEventRepository.class);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        stockClient = mock(TicketStockClient.class);

        when(redisson.getLock(anyString())).thenReturn(lock);
        org.redisson.api.RBucket<String> emptyBucket = mock(org.redisson.api.RBucket.class);
        when(emptyBucket.isExists()).thenReturn(false);
        when(redisson.<String>getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(emptyBucket);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        when(bookings.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            if (booking.getId() == null) booking.setId("booking-" + ids.incrementAndGet());
            return booking;
        });

        properties = new GaReservationProperties();
        properties.setEnabled(true);
        properties.setInternalToken(TOKEN);
        GaReservationService ga = new GaReservationService(properties, stockClient, new ObjectMapper());
        service = new BookingServiceImpl(bookings, seatReservations, outbox, redisson, redis, new ObjectMapper(),
                mock(EventServiceClient.class), mock(TicketServiceClient.class), ga, mock(OrderLimitValidator.class));
    }

    private static BookingItemRequest item(String typeId, int quantity, Boolean ga) {
        BookingItemRequest item = new BookingItemRequest();
        item.setTicketTypeId(typeId);
        item.setQuantity(quantity);
        item.setUnitPrice(100000d);
        item.setGeneralAdmission(ga);
        return item;
    }

    private static BookingCreateRequest request(String customer, List<String> seatIds, BookingItemRequest... items) {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setCustomerId(customer);
        request.setEventId("event-1");
        request.setShowtimeId("show-1");
        request.setSeatIds(seatIds);
        request.setItems(List.of(items));
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<SeatReservation> savedSeatReservations() {
        ArgumentCaptor<Iterable<SeatReservation>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(seatReservations).saveAll(captor.capture());
        List<SeatReservation> out = new ArrayList<>();
        captor.getValue().forEach(out::add);
        return out;
    }

    // --- flag off: today's behaviour -----------------------------------------------

    @Test
    void flagOffKeepsVirtualSeatHoldsAndNeverTouchesTicketStock() throws Exception {
        properties.setEnabled(false);

        BookingResponse response = service.createBooking(request("u1", List.of(GA + "-STT-1", GA + "-STT-2"), item(GA, 2, null)));

        assertThat(response.getId()).isEqualTo("booking-1");
        assertThat(savedSeatReservations()).extracting(SeatReservation::getSeatId)
                .containsExactly(GA + "-STT-1", GA + "-STT-2");
        verify(valueOps, times(2)).set(startsWith("seat:hold:showtime:show-1:" + GA + "-STT-"), eq("booking-1"), eq(15L), eq(TimeUnit.MINUTES));
        verify(lock).tryLock(3, 5, TimeUnit.SECONDS);
        verifyNoInteractions(stockClient);
    }

    // --- flag on: general admission -------------------------------------------------

    @Test
    void itemsOnlyOrderHoldsStockAndCreatesNoSeatsNoRedisKeysAndNoEventLock() throws Exception {
        BookingResponse response = service.createBooking(request("u1", List.of(), item(GA, 2, true)));

        assertThat(response.getId()).isEqualTo("booking-1");
        verify(stockClient).reserve(eq(TOKEN), any(Map.class));
        verify(seatReservations, never()).saveAll(any());
        verifyNoInteractions(valueOps, setOps);
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookings, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getGaReserved()).isTrue();
        assertThat(saved.getValue().getItems()).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualTo(2));
    }

    @Test
    void olderClientVirtualSeatsNeitherHoldSeatsNorRedisKeysWhenStockIsHeld() throws Exception {
        service.createBooking(request("u1", List.of(GA + "-STT-7", GA + "-STT-8"), item(GA, 2, null)));

        verify(stockClient).reserve(eq(TOKEN), any(Map.class));
        verify(seatReservations, never()).saveAll(any());
        verifyNoInteractions(valueOps, setOps);
    }

    @Test
    void soldOutReturns409AndLeavesNoSeatOrRedisSideEffects() {
        doThrow(soldOut()).when(stockClient).reserve(anyString(), any());

        assertThatThrownBy(() -> service.createBooking(request("u1", List.of(), item(GA, 2, true))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("TICKET_SOLD_OUT");
                });

        verify(seatReservations, never()).saveAll(any());
        verify(outbox, never()).save(any());
        verifyNoInteractions(valueOps, setOps);
    }

    // --- flag on: seat flows are unchanged -------------------------------------------

    @Test
    void seatmapOrderWithFlagOnIsHandledExactlyLikeBefore() throws Exception {
        service.createBooking(request("u1", List.of("seat_1_2", "seat_1_3"), item(VIP, 2, null)));

        assertThat(savedSeatReservations()).extracting(SeatReservation::getSeatId).containsExactly("seat_1_2", "seat_1_3");
        verify(valueOps).set("seat:hold:showtime:show-1:seat_1_2", "booking-1", 15, TimeUnit.MINUTES);
        verify(valueOps).set("seat:hold:showtime:show-1:seat_1_3", "booking-1", 15, TimeUnit.MINUTES);
        verify(lock).tryLock(3, 5, TimeUnit.SECONDS);
        verifyNoInteractions(stockClient);
    }

    @Test
    void mixedOrderHoldsStockForGaAndSeatsForTheRest() throws Exception {
        service.createBooking(request("u1", List.of("seat_1_2"), item(VIP, 1, null), item(GA, 3, true)));

        assertThat(savedSeatReservations()).extracting(SeatReservation::getSeatId).containsExactly("seat_1_2");
        verify(stockClient).reserve(eq(TOKEN), any(Map.class));
        verify(valueOps).set("seat:hold:showtime:show-1:seat_1_2", "booking-1", 15, TimeUnit.MINUTES);
        verify(lock).tryLock(3, 5, TimeUnit.SECONDS);
    }

    // --- concurrency (fake ticket-service with an atomic counter) -------------------

    @Test
    void twentyConcurrentOrdersForFiveLastTicketsGiveFiveBookingsAndFifteenSoldOut() throws Exception {
        AtomicInteger remaining = new AtomicInteger(5);
        doAnswer(invocation -> {
            int wanted = (Integer) ((List<Map<String, Object>>) ((Map<String, Object>) invocation.getArgument(1)).get("items"))
                    .get(0).get("quantity");
            while (true) {
                int current = remaining.get();
                if (current < wanted) throw soldOut();
                if (remaining.compareAndSet(current, current - wanted)) return null;
            }
        }).when(stockClient).reserve(anyString(), any());

        int buyers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch ready = new CountDownLatch(buyers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String customer = "user-" + i;
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    service.createBooking(request(customer, List.of(), item(GA, 1, true)));
                    return true;
                } catch (ResponseStatusException e) {
                    assertThat(e.getReason()).isEqualTo("TICKET_SOLD_OUT");
                    return false;
                }
            }));
        }
        ready.await();
        go.countDown();
        int success = 0;
        for (Future<Boolean> result : results) if (result.get()) success++;
        pool.shutdown();

        assertThat(success).isEqualTo(5);
        assertThat(remaining.get()).isZero();
        verify(seatReservations, never()).saveAll(any());
    }

    private static feign.FeignException.Conflict soldOut() {
        feign.Request request = feign.Request.create(feign.Request.HttpMethod.POST, "http://ticket-service/x", Map.of(), null,
                java.nio.charset.StandardCharsets.UTF_8, null);
        return new feign.FeignException.Conflict("conflict", request,
                "{\"code\":\"TICKET_SOLD_OUT\",\"available\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
    }

    private static String startsWith(String prefix) {
        return org.mockito.ArgumentMatchers.startsWith(prefix);
    }
}
