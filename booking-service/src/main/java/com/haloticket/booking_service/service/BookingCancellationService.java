package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.OutboxStatus;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingCancellationService {

    private final BookingRepository bookingRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SeatWebsocketService websocketService;
    private final GaReservationService gaReservationService;

    @Transactional
    public BookingResponse cancelOwnBooking(String idOrCode, String customerId) {
        Booking booking = bookingRepository.findByIdForUpdate(idOrCode)
                .or(() -> bookingRepository.findByBookingCode(idOrCode)
                        .flatMap(found -> bookingRepository.findByIdForUpdate(found.getId())))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        // same answer for "missing" and "someone else's" so ids cannot be probed.
        if (booking.getCustomerId() == null || !booking.getCustomerId().equals(customerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return toResponse(booking);
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BOOKING_NOT_CANCELLABLE");
        }

        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        List<SeatReservation> reservations = seatReservationRepository.findByBooking(booking);
        for (SeatReservation res : reservations) {
            res.setStatus(SeatReservationStatus.CANCELLED);
            String showtimeId = booking.getShowtimeId() != null ? booking.getShowtimeId() : res.getShowtimeId();
            String prefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + booking.getEventId();
            stringRedisTemplate.delete("seat:hold:" + prefix + ":" + res.getSeatId());
            stringRedisTemplate.opsForSet().remove(prefix + ":held_seats", res.getSeatId());
            websocketService.broadcastSeatStatus(booking.getEventId(), showtimeId, res.getSeatId(), "AVAILABLE");
        }
        seatReservationRepository.saveAll(reservations);
        gaReservationService.releaseIfReserved(booking);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("bookingId", booking.getId());
            payload.put("bookingCode", booking.getBookingCode());
            payload.put("eventId", booking.getEventId());
            payload.put("customerId", booking.getCustomerId());
            payload.put("reason", "USER_CANCELLED");
            outboxEventRepository.save(OutboxEvent.builder()
                    .aggregateType("BOOKING")
                    .aggregateId(booking.getId())
                    .eventType("BOOKING_CANCELLED")
                    .topic("haloticket.booking.cancelled.v1")
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build());
        } catch (Exception e) {
            log.error("Error writing outbox event for cancelled booking {}", booking.getId(), e);
        }
        return toResponse(booking);
    }

    private BookingResponse toResponse(Booking booking) {
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
}
