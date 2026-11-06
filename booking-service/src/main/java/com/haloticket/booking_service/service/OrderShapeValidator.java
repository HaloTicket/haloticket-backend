package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Early shape check of an order, before anything touches Redis, the database or ticket-service: an order that would
 * fail at ticket issuance after payment is refused here with 422 instead. Pure; needs no other service.
 * Codes: INVALID_ORDER_ITEMS, DUPLICATE_SEAT_IDS, SEAT_COUNT_EXCEEDS_QUANTITY, SEAT_COUNT_MISMATCH.
 */
@Slf4j
@Component
public class OrderShapeValidator {

    public void validate(BookingCreateRequest request) {
        if (request == null) return;
        List<String> seatIds = request.getSeatIds() == null ? List.of() : request.getSeatIds();
        List<BookingItemRequest> items = request.getItems() == null ? List.of() : request.getItems();
        boolean hasSeats = !seatIds.isEmpty();

        if (hasSeats && items.isEmpty()) {
            throw refuse("INVALID_ORDER_ITEMS", "seats without items");
        }

        long totalQuantity = 0;
        boolean anyGeneralAdmission = false;
        for (BookingItemRequest item : items) {
            if (item == null || item.getTicketTypeId() == null || item.getTicketTypeId().isBlank()
                    || item.getQuantity() == null || item.getQuantity() < 1) {
                throw refuse("INVALID_ORDER_ITEMS", "item without ticket type or with quantity below 1");
            }
            totalQuantity += item.getQuantity();
            if (Boolean.TRUE.equals(item.getGeneralAdmission())) anyGeneralAdmission = true;
        }

        if (hasSeats) {
            Set<String> seen = new HashSet<>();
            for (String seatId : seatIds) {
                if (seatId == null || seatId.isBlank() || !seen.add(seatId)) {
                    throw refuse("DUPLICATE_SEAT_IDS", "blank or repeated seat id");
                }
            }
            if (seatIds.size() > totalQuantity) {
                throw refuse("SEAT_COUNT_EXCEEDS_QUANTITY", "seats=" + seatIds.size() + " quantity=" + totalQuantity);
            }
            // a seat-only order has one ticket per seat; a mixed order cannot be checked more tightly because a seat id
            // does not say which ticket type it belongs to
            if (!anyGeneralAdmission && seatIds.size() != totalQuantity) {
                throw refuse("SEAT_COUNT_MISMATCH", "seats=" + seatIds.size() + " quantity=" + totalQuantity);
            }
        }
    }

    private static ResponseStatusException refuse(String code, String detail) {
        log.warn("[ORDER_SHAPE] refused {}: {}", code, detail);
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, code);
    }
}
