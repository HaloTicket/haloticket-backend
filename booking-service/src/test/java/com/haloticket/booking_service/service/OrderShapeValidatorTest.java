package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderShapeValidatorTest {

    private final OrderShapeValidator validator = new OrderShapeValidator();

    private static BookingItemRequest item(String typeId, Integer quantity, Boolean ga) {
        BookingItemRequest item = new BookingItemRequest();
        item.setTicketTypeId(typeId);
        item.setQuantity(quantity);
        item.setGeneralAdmission(ga);
        return item;
    }

    private static BookingCreateRequest order(List<String> seatIds, BookingItemRequest... items) {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setSeatIds(seatIds);
        request.setItems(new ArrayList<>(Arrays.asList(items)));
        return request;
    }

    private void refused(BookingCreateRequest request, String code) {
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.getReason()).isEqualTo(code);
        });
    }

    // --- valid orders -----------------------------------------------------------

    @Test
    void gaOnlyOrderPasses() {
        assertThatCode(() -> validator.validate(order(List.of(), item("t1", 3, true)))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(order(null, item("t1", 3, null)))).doesNotThrowAnyException();
    }

    @Test
    void seatOnlyOrderWithOneSeatPerUnitPasses() {
        assertThatCode(() -> validator.validate(order(List.of("s1", "s2"), item("t1", 2, null)))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(order(List.of("s1", "s2", "s3"), item("t1", 1, null), item("t2", 2, false))))
                .doesNotThrowAnyException();
    }

    @Test
    void mixedOrderPassesWhenSeatsDoNotExceedQuantity() {
        assertThatCode(() -> validator.validate(order(List.of("s1", "s2"), item("t1", 2, null), item("ga", 3, true))))
                .doesNotThrowAnyException();
        // fewer seats than seated units is fine for a mixed order: a seat id does not say which type it belongs to
        assertThatCode(() -> validator.validate(order(List.of("s1"), item("t1", 2, null), item("ga", 3, true))))
                .doesNotThrowAnyException();
    }

    @Test
    void orderWithoutItemsAndWithoutSeatsIsLeftToTheExistingFlow() {
        BookingCreateRequest request = new BookingCreateRequest();
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    // --- INVALID_ORDER_ITEMS ----------------------------------------------------

    @Test
    void seatsWithoutItemsAreRefused() {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setSeatIds(List.of("s1"));
        refused(request, "INVALID_ORDER_ITEMS");
        request.setItems(List.of());
        refused(request, "INVALID_ORDER_ITEMS");
    }

    @Test
    void everyItemNeedsATicketTypeAndAQuantityOfAtLeastOne() {
        refused(order(List.of(), item(null, 1, true)), "INVALID_ORDER_ITEMS");
        refused(order(List.of(), item("  ", 1, true)), "INVALID_ORDER_ITEMS");
        refused(order(List.of(), item("t1", null, true)), "INVALID_ORDER_ITEMS");
        refused(order(List.of(), item("t1", 0, true)), "INVALID_ORDER_ITEMS");
        refused(order(List.of(), item("t1", -2, null)), "INVALID_ORDER_ITEMS");
        // an item that is not general admission is checked too
        refused(order(List.of("s1"), item("t1", 1, null), item("t2", null, false)), "INVALID_ORDER_ITEMS");
        refused(order(List.of("s1"), item("t1", 1, null), item("", 1, false)), "INVALID_ORDER_ITEMS");
    }

    // --- DUPLICATE_SEAT_IDS -----------------------------------------------------

    @Test
    void seatIdsMustBeNonBlankAndUnique() {
        refused(order(List.of("s1", "s1"), item("t1", 2, null)), "DUPLICATE_SEAT_IDS");
        refused(order(Arrays.asList("s1", null), item("t1", 2, null)), "DUPLICATE_SEAT_IDS");
        refused(order(List.of("s1", " "), item("t1", 2, null)), "DUPLICATE_SEAT_IDS");
    }

    // --- counts -----------------------------------------------------------------

    @Test
    void moreSeatsThanQuantityIsRefusedForEveryKindOfOrder() {
        refused(order(List.of("s1", "s2", "s3"), item("t1", 2, null)), "SEAT_COUNT_EXCEEDS_QUANTITY");
        refused(order(List.of("s1", "s2", "s3"), item("t1", 1, null), item("ga", 1, true)), "SEAT_COUNT_EXCEEDS_QUANTITY");
    }

    @Test
    void seatOnlyOrderNeedsSeatsEqualToQuantity() {
        // body quantity differs from the seat count
        refused(order(List.of("s1", "s2"), item("t1", 3, null)), "SEAT_COUNT_MISMATCH");
        refused(order(List.of("s1"), item("t1", 2, null), item("t2", 1, false)), "SEAT_COUNT_MISMATCH");
    }
}
