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
import org.redisson.client.codec.StringCodec;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Override
    @Transactional
    public BookingResponse createBooking(BookingCreateRequest request) {
        String eventId = request.getEventId();
        List<String> seatIds = request.getSeatIds();

        // 1. acquire Redisson Distributed Lock for Event
        String lockKey = "lock:event:" + eventId + ":booking";
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // wait up to 3 seconds for lock, auto-release after 5 seconds to prevent deadlocks
            boolean isLocked = lock.tryLock(3, 5, TimeUnit.SECONDS);
            if (!isLocked) {
                throw new RuntimeException("Khu vực đặt vé đang bận, vui lòng thử lại sau giây lát.");
            }

            // 2. check seat availability in Redis
            if (seatIds != null && !seatIds.isEmpty()) {
                String currentCustomerId = request.getCustomerId() != null ? request.getCustomerId() : "ANONYMOUS";
                for (String seatId : seatIds) {
                    String seatHoldKey = "seat:hold:" + eventId + ":" + seatId;
                    RBucket<String> bucket = redissonClient.getBucket(seatHoldKey, new StringCodec());
                    if (bucket.isExists()) {
                        String heldBy = bucket.get();
                        if (!currentCustomerId.equals(heldBy)) {
                            if (heldBy != null && heldBy.startsWith("session_")) {
                                log.info("Seat {} held by session {}, allowing user {} to book it", seatId, heldBy, currentCustomerId);
                            } else {
                                throw new RuntimeException("Ghế " + seatId + " vừa mới được giữ bởi người dùng khác.");
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
                    double itemSubtotal = (itemReq.getUnitPrice() != null ? itemReq.getUnitPrice() : 0.0) * (itemReq.getQuantity() != null ? itemReq.getQuantity() : 1);
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

            Booking booking = Booking.builder()
                    .bookingCode(bookingCode)
                    .customerId(request.getCustomerId() != null ? request.getCustomerId() : "ANONYMOUS")
                    .customerEmail(request.getCustomerEmail())
                    .customerName(request.getCustomerName())
                    .eventId(eventId)
                    .voucherId(request.getVoucherId())
                    .subtotal(subtotal)
                    .discountAmount(0.0)
                    .totalAmount(request.getTotalAmount())
                    .status(BookingStatus.PENDING)
                    .expiredAt(expiresAt)
                    .items(new ArrayList<>())
                    .build();
                    
            for (BookingItem bItem : bookingItems) {
                bItem.setBooking(booking);
                booking.getItems().add(bItem);
            }

            Booking savedBooking = bookingRepository.save(booking);

            // 4. reserve seats & set 15-min hold TTL in Redis
            List<SeatReservation> reservations = new ArrayList<>();
            if (seatIds != null && !seatIds.isEmpty()) {
                for (String seatId : seatIds) {
                    SeatReservation reservation = SeatReservation.builder()
                            .booking(savedBooking)
                            .seatId(seatId)
                            .status(SeatReservationStatus.HOLDING)
                            .lockExpiresAt(expiresAt)
                            .build();
                    reservations.add(reservation);

                    // Set hold TTL key in Redis (900 seconds = 15 mins)
                    String seatHoldKey = "seat:hold:" + eventId + ":" + seatId;
                    stringRedisTemplate.opsForValue().set(seatHoldKey, savedBooking.getId(), 15, TimeUnit.MINUTES);
                    stringRedisTemplate.opsForSet().add("event:" + eventId + ":held_seats", seatId);
                }
                seatReservationRepository.saveAll(reservations);
            }

            // 5. Save Outbox Event (BOOKING_CREATED)
            try {
                Map<String, Object> eventPayload = new HashMap<>();
                eventPayload.put("bookingId", savedBooking.getId());
                eventPayload.put("bookingCode", savedBooking.getBookingCode());
                eventPayload.put("customerId", savedBooking.getCustomerId());
                eventPayload.put("eventId", savedBooking.getEventId());
                eventPayload.put("totalAmount", savedBooking.getTotalAmount());
                eventPayload.put("expiredAt", savedBooking.getExpiredAt() != null ? savedBooking.getExpiredAt().toString() : null);
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

            log.info("Booking created successfully with Redisson Distributed Lock: bookingCode={}, id={}", bookingCode, savedBooking.getId());

            return BookingResponse.builder()
                    .id(savedBooking.getId())
                    .bookingCode(savedBooking.getBookingCode())
                    .customerId(savedBooking.getCustomerId())
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
}