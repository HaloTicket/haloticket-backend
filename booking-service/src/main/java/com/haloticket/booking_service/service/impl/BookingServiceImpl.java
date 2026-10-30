package com.haloticket.booking_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.OutboxStatus;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.service.BookingService;
import com.haloticket.booking_service.service.GaReservationService;
import com.haloticket.booking_service.client.TicketServiceClient;
import com.haloticket.booking_service.service.OrderLimitValidator;
import com.haloticket.booking_service.dto.response.BookingDetailResponse;
import com.haloticket.booking_service.dto.response.TicketTypeDto;
import org.redisson.client.codec.StringCodec;

import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.dto.response.EventResponse;
import com.haloticket.booking_service.dto.response.VoucherResponse;
import com.haloticket.booking_service.dto.response.EventShowtimeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingServiceImpl implements BookingService {

    private final BookingRepository bookingRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final EventServiceClient eventServiceClient;
    private final TicketServiceClient ticketServiceClient;
    private final GaReservationService gaReservationService;
    private final OrderLimitValidator orderLimitValidator;

    @Override
    @Transactional
    public BookingResponse createBooking(BookingCreateRequest request) {
        String eventId = request.getEventId();
        // with booking.ga.reservation.enabled off this is the request untouched.
        GaReservationService.Plan gaPlan = gaReservationService.plan(request);
        List<String> seatIds = gaPlan.seatIds();
        // 422 QUANTITY_OUT_OF_RANGE when the quantity of a ticket type is below minPerOrder or above maxPerOrder
        orderLimitValidator.validate(request);

        // 1. acquire Redisson Distributed Lock for Event
        String lockKey = "lock:event:" + eventId + ":booking";
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // validate showtime sale time
            if (request.getShowtimeId() != null) {
                EventResponse eventResp = eventServiceClient.getEventById(eventId);
                if (eventResp != null && eventResp.getShowtimes() != null) {
                    EventShowtimeDto showtime = eventResp.getShowtimes().stream()
                            .filter(st -> request.getShowtimeId().equals(st.getId()))
                            .findFirst().orElse(null);
                    if (showtime != null) {
                        LocalDateTime now = LocalDateTime.now();
                        if (showtime.getSaleStartTime() != null && now.isBefore(showtime.getSaleStartTime())) {
                            throw new RuntimeException("Suất diễn chưa mở bán!");
                        }
                        if (showtime.getSaleEndTime() != null && now.isAfter(showtime.getSaleEndTime())) {
                            throw new RuntimeException("Suất diễn đã kết thúc mở bán!");
                        }
                    }
                }
            }

            // wait up to 3 seconds for lock, auto-release after 5 seconds to prevent
            // deadlocks
            boolean stockOnlyOrder = gaPlan.hasGa() && !gaPlan.hasSeats();
            if (!stockOnlyOrder) {
                boolean isLocked = lock.tryLock(3, 5, TimeUnit.SECONDS);
                if (!isLocked) {
                    throw new RuntimeException("Khu vực đặt vé đang bận, vui lòng thử lại sau giây lát.");
                }
            }

            // 2. check seat availability in Redis
            if (seatIds != null && !seatIds.isEmpty()) {
                String currentCustomerId = request.getCustomerId() != null ? request.getCustomerId() : "ANONYMOUS";
                String showtimePrefix = request.getShowtimeId() != null ? "showtime:" + request.getShowtimeId()
                        : "event:" + eventId;
                for (String seatId : seatIds) {
                    String seatHoldKey = "seat:hold:" + showtimePrefix + ":" + seatId;
                    RBucket<String> bucket = redissonClient.getBucket(seatHoldKey, new StringCodec());
                    if (bucket.isExists()) {
                        String heldBy = bucket.get();
                        if (!currentCustomerId.equals(heldBy)) {
                            if (heldBy != null && heldBy.startsWith("session_")) {
                                log.info("Seat {} held by session {}, allowing user {} to book it", seatId, heldBy,
                                        currentCustomerId);
                            } else {
                                boolean isOwnPendingBooking = false;
                                if (heldBy != null) {
                                    Optional<Booking> previousBooking = bookingRepository.findByIdForUpdate(heldBy);
                                    if (previousBooking.isPresent()
                                            && previousBooking.get().getStatus() == BookingStatus.PENDING
                                            && currentCustomerId.equals(previousBooking.get().getCustomerId())) {
                                        log.info("Seat {} held by previous pending booking {} of same customer {}, superseding old booking",
                                                seatId, heldBy, currentCustomerId);
                                        Booking oldBooking = previousBooking.get();
                                        oldBooking.setStatus(BookingStatus.CANCELLED);
                                        bookingRepository.save(oldBooking);
                                        isOwnPendingBooking = true;
                                    } else if (previousBooking.isEmpty()) {
                                        log.warn("Seat {} held by non-existent booking ID {} (likely rolled back transaction), clearing orphaned hold",
                                                seatId, heldBy);
                                        isOwnPendingBooking = true;
                                    }
                                }
                                if (!isOwnPendingBooking) {
                                    throw new IllegalArgumentException("Ghế " + seatId + " vừa mới được giữ bởi người dùng khác.");
                                }
                            }
                        }
                    }
                }
            }

            // 3. create Booking DB record with 15-min expiration TTL
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiresAt = now.plusMinutes(15);
            String bookingCode = "HALO-" + System.currentTimeMillis();

            double subtotal = 0.0;
            List<BookingItem> bookingItems = new ArrayList<>();
            if (request.getItems() != null && !request.getItems().isEmpty()) {
                for (BookingItemRequest itemReq : request.getItems()) {
                    double itemSubtotal = (itemReq.getUnitPrice() != null ? itemReq.getUnitPrice() : 0.0)
                            * (itemReq.getQuantity() != null ? itemReq.getQuantity() : 1);
                    subtotal += itemSubtotal;
                    BookingItem bItem = BookingItem.builder()
                            .ticketTypeId(itemReq.getTicketTypeId())
                            .quantity(itemReq.getQuantity())
                            .unitPrice(itemReq.getUnitPrice() != null ? itemReq.getUnitPrice() : 0.0)
                            .subTotal(itemSubtotal)
                            .build();
                    bookingItems.add(bItem);
                }
            } else {
                subtotal = request.getTotalAmount() != null ? request.getTotalAmount() : 0.0;
            }

            double discountAmount = 0.0;
            if (request.getVoucherId() != null && !request.getVoucherId().isEmpty()) {
                List<VoucherResponse> vouchers = eventServiceClient.getVouchersByEventId(eventId);
                VoucherResponse appliedVoucher = null;
                if (vouchers != null) {
                    appliedVoucher = vouchers.stream()
                            .filter(v -> request.getVoucherId().equals(v.getId()))
                            .findFirst()
                            .orElse(null);
                }

                if (appliedVoucher != null && (appliedVoucher.getIsActive() != null && appliedVoucher.getIsActive())) {
                    if ("PERCENTAGE".equals(appliedVoucher.getDiscountType())) {
                        discountAmount = Math.round(subtotal * (appliedVoucher.getDiscountValue() / 100.0));
                    } else {
                        discountAmount = appliedVoucher.getDiscountValue() != null ? appliedVoucher.getDiscountValue()
                                : 0.0;
                    }
                    discountAmount = Math.min(discountAmount, subtotal);
                }
            }

            double finalTotal = Math.max(0.0, subtotal - discountAmount);

            Booking booking = Booking.builder()
                    .bookingCode(bookingCode)
                    .customerId(request.getCustomerId() != null ? request.getCustomerId() : "ANONYMOUS")
                    .customerEmail(request.getCustomerEmail())
                    .customerName(request.getCustomerName())
                    .eventId(eventId)
                    .showtimeId(request.getShowtimeId())
                    .voucherId(request.getVoucherId())
                    .subtotal(subtotal)
                    .discountAmount(discountAmount)
                    .totalAmount(finalTotal)
                    .status(BookingStatus.PENDING)
                    .expiredAt(expiresAt)
                    .items(new ArrayList<>())
                    .build();

            for (BookingItem bItem : bookingItems) {
                bItem.setBooking(booking);
                booking.getItems().add(bItem);
            }

            Booking savedBooking = bookingRepository.save(booking);

            // 3b. hold general-admission stock; sold out / unavailable rolls this booking back
            if (gaPlan.hasGa()) {
                gaReservationService.reserve(savedBooking.getId(), request, gaPlan, expiresAt);
                savedBooking.setGaReserved(true);
            }

            // 4. reserve seats & set 15-min hold TTL in Redis
            List<SeatReservation> reservations = new ArrayList<>();
            if (seatIds != null && !seatIds.isEmpty()) {
                for (String seatId : seatIds) {
                    SeatReservation reservation = SeatReservation.builder()
                            .booking(savedBooking)
                            .seatId(seatId)
                            .showtimeId(request.getShowtimeId())
                            .status(SeatReservationStatus.HOLDING)
                            .lockExpiresAt(expiresAt)
                            .build();
                    reservations.add(reservation);

                    // set hold TTL key in Redis (900 seconds = 15 mins)
                    String showtimePrefix = request.getShowtimeId() != null ? "showtime:" + request.getShowtimeId()
                            : "event:" + eventId;
                    String seatHoldKey = "seat:hold:" + showtimePrefix + ":" + seatId;
                    stringRedisTemplate.opsForValue().set(seatHoldKey, savedBooking.getId(), 15, TimeUnit.MINUTES);
                    stringRedisTemplate.opsForSet().add(showtimePrefix + ":held_seats", seatId);
                }
                seatReservationRepository.saveAll(reservations);
            }

            // 5. save Outbox Event (BOOKING_CREATED)
            try {
                Map<String, Object> eventPayload = new HashMap<>();
                eventPayload.put("bookingId", savedBooking.getId());
                eventPayload.put("bookingCode", savedBooking.getBookingCode());
                eventPayload.put("customerId", savedBooking.getCustomerId());
                eventPayload.put("eventId", savedBooking.getEventId());
                eventPayload.put("totalAmount", savedBooking.getTotalAmount());
                eventPayload.put("expiredAt",
                        savedBooking.getExpiredAt() != null ? savedBooking.getExpiredAt().toString() : null);
                eventPayload.put("seatIds", seatIds);

                OutboxEvent outboxEvent = OutboxEvent.builder()
                        .aggregateType("BOOKING")
                        .aggregateId(savedBooking.getId())
                        .eventType("BOOKING_CREATED")
                        .topic("haloticket.booking.created.v1")
                        .payload(objectMapper.writeValueAsString(eventPayload))
                        .status(OutboxStatus.PENDING)
                        .retryCount(0)
                        .build();

                outboxEventRepository.save(outboxEvent);
            } catch (Exception e) {
                log.error("Failed to create outbox event for booking {}", savedBooking.getId(), e);
            }

            log.info("Booking created successfully with Redisson Distributed Lock: bookingCode={}, id={}", bookingCode,
                    savedBooking.getId());

            return BookingResponse.builder()
                    .id(savedBooking.getId())
                    .bookingCode(savedBooking.getBookingCode())
                    .customerId(savedBooking.getCustomerId())
                    .customerName(savedBooking.getCustomerName())
                    .customerEmail(savedBooking.getCustomerEmail())
                    .eventId(savedBooking.getEventId())
                    .totalAmount(savedBooking.getTotalAmount())
                    .status(savedBooking.getStatus())
                    .expiredAt(savedBooking.getExpiredAt())
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Quá trình xử lý khóa đặt vé bị gián đoạn", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponse getBookingById(String idOrCode) {
        Booking booking = bookingRepository.findById(idOrCode)
                .or(() -> bookingRepository.findByBookingCode(idOrCode))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        return BookingResponse.builder()
                .id(booking.getId())
                .bookingCode(booking.getBookingCode())
                .customerId(booking.getCustomerId())
                .customerName(booking.getCustomerName())
                .customerEmail(booking.getCustomerEmail())
                .eventId(booking.getEventId())
                .totalAmount(booking.getTotalAmount())
                .status(booking.getStatus())
                .expiredAt(booking.getExpiredAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public BookingDetailResponse getBookingByCode(String bookingCode, String customerId) {
        Booking booking = bookingRepository.findByBookingCode(bookingCode)
                .or(() -> bookingRepository.findById(bookingCode))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        if (booking.getCustomerId() == null || !booking.getCustomerId().equals(customerId)) {
            // return 404 instead of 403 to prevent enumeration
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
        }

        Map<String, String> typeNames = new HashMap<>();
        List<BookingDetailResponse.BookingItemDetailResponse> itemDetails = new ArrayList<>();
        if (booking.getItems() != null) {
            for (BookingItem item : booking.getItems()) {
                String typeName = typeNames.computeIfAbsent(item.getTicketTypeId(), id -> {
                    try {
                        TicketTypeDto dto = ticketServiceClient.getTicketTypeById(id);
                        if (dto != null && dto.getName() != null && !dto.getName().isBlank()) {
                            return dto.getName();
                        }
                    } catch (Exception e) {
                        log.warn("Could not fetch ticket type name for id {}: {}", id, e.getMessage());
                    }
                    return "Vé sự kiện";
                });

                itemDetails.add(BookingDetailResponse.BookingItemDetailResponse.builder()
                        .ticketTypeId(item.getTicketTypeId())
                        .ticketTypeName(typeName)
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build());
            }
        }

        return BookingDetailResponse.builder()
                .id(booking.getId())
                .bookingCode(booking.getBookingCode())
                .status(booking.getStatus())
                .eventId(booking.getEventId())
                .showtimeId(booking.getShowtimeId())
                .totalAmount(booking.getTotalAmount())
                .createdAt(booking.getCreatedAt())
                .items(itemDetails)
                .build();
    }
}
