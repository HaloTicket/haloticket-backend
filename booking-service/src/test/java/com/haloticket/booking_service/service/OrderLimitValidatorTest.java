package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.TicketOrderLimitClient;
import com.haloticket.booking_service.client.TicketServiceClient;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import com.haloticket.booking_service.dto.response.TicketTypeDto;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.service.impl.BookingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The server-side minPerOrder / maxPerOrder check of an order. */
class OrderLimitValidatorTest {

    private TicketOrderLimitClient tickets;
    private OrderLimitValidator validator;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketOrderLimitClient.class);
        validator = new OrderLimitValidator(tickets);
        limits("t1", 2, 5);
    }

    private void limits(String id, Integer min, Integer max) {
        TicketTypeDto dto = new TicketTypeDto();
        dto.setId(id);
        dto.setMinPerOrder(min);
        dto.setMaxPerOrder(max);
        when(tickets.getTicketTypeById(id)).thenReturn(dto);
    }

    private static BookingCreateRequest order(Object... idAndQuantity) {
        BookingCreateRequest request = new BookingCreateRequest();
        List<BookingItemRequest> items = new java.util.ArrayList<>();
        for (int i = 0; i < idAndQuantity.length; i += 2) {
            BookingItemRequest item = new BookingItemRequest();
            item.setTicketTypeId((String) idAndQuantity[i]);
            item.setQuantity((Integer) idAndQuantity[i + 1]);
            items.add(item);
        }
        request.setItems(items);
        return request;
    }

    @Test
    void quantityEqualToTheMinimumOrMaximumPasses() {
        assertThatCode(() -> validator.validate(order("t1", 2))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(order("t1", 5))).doesNotThrowAnyException();
    }

    @Test
    void belowTheMinimumAndAboveTheMaximumAreRefusedWith422() {
        for (int quantity : new int[]{1, 6}) {
            assertThatThrownBy(() -> validator.validate(order("t1", quantity)))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.getReason()).isEqualTo("QUANTITY_OUT_OF_RANGE");
                    });
        }
    }

    @Test
    void repeatedItemsOfOneTypeAreAddedUp() {
        assertThatThrownBy(() -> validator.validate(order("t1", 3, "t1", 3))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aFailingTicketServiceNeverBlocksTheOrder() {
        when(tickets.getTicketTypeById("t2")).thenThrow(new RuntimeException("connect timed out"));
        assertThatCode(() -> validator.validate(order("t2", 99))).doesNotThrowAnyException();
        when(tickets.getTicketTypeById("t3")).thenReturn(null);
        assertThatCode(() -> validator.validate(order("t3", 99))).doesNotThrowAnyException();
    }

    @Test
    void missingLimitsAreNotChecked() {
        limits("t4", null, null);
        assertThatCode(() -> validator.validate(order("t4", 500))).doesNotThrowAnyException();
    }

    @Test
    void theLimitsAreCachedForAShortTime() {
        validator.validate(order("t1", 3));
        validator.validate(order("t1", 4));
        verify(tickets, times(1)).getTicketTypeById("t1");
    }

    private static BookingCreateRequest order(List<String> seatIds, Object... idAndQuantity) {
        BookingCreateRequest request = order(idAndQuantity);
        request.setSeatIds(seatIds);
        return request;
    }

    private static BookingCreateRequest gaOrder(String id, int quantity) {
        BookingCreateRequest request = order(id, quantity);
        request.getItems().get(0).setGeneralAdmission(true);
        return request;
    }

    @Test
    void standingOrderUsesTheItemQuantity() {
        assertThatCode(() -> validator.validate(gaOrder("t1", 3))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(gaOrder("t1", 6))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> validator.validate(gaOrder("t1", 1))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void seatedOrderUsesTheItemQuantityWhichIsTheNumberOfTicketsIssued() {
        assertThatCode(() -> validator.validate(order(List.of("s1", "s2", "s3"), "t1", 3))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(order(List.of("s1", "s2", "s3", "s4", "s5", "s6"), "t1", 6)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void whenTheBodyQuantityDiffersFromTheSeatCountTheQuantityDecides() {
        // 1 ticket would be issued (ticket-service creates one per unit of quantity), so 1 < min 2 is refused
        assertThatThrownBy(() -> validator.validate(order(List.of("s1", "s2", "s3"), "t1", 1)))
                .isInstanceOf(ResponseStatusException.class);
        // 4 tickets are issued although only 2 seats were picked: 4 is inside 2..5, so the order passes
        assertThatCode(() -> validator.validate(order(List.of("s1", "s2"), "t1", 4))).doesNotThrowAnyException();
    }

    @Test
    void mixedOrderIsCheckedPerTicketType() {
        limits("t2", 1, 2);
        // seated type t1 (3 in 2..5) together with standing type t2 (2 in 1..2)
        BookingCreateRequest ok = order(List.of("s1", "s2", "s3"), "t1", 3, "t2", 2);
        ok.getItems().get(1).setGeneralAdmission(true);
        assertThatCode(() -> validator.validate(ok)).doesNotThrowAnyException();
        // the standing line is over its own maximum although the seated one is fine
        BookingCreateRequest bad = order(List.of("s1", "s2", "s3"), "t1", 3, "t2", 3);
        bad.getItems().get(1).setGeneralAdmission(true);
        assertThatThrownBy(() -> validator.validate(bad)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void nonPositiveQuantitiesDoNotOffsetOthers() {
        assertThatThrownBy(() -> validator.validate(order("t1", 7, "t1", -3))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aSlowTicketServiceIsSkippedWithinTheTimeout() {
        OrderLimitValidator quick = new OrderLimitValidator(tickets, 200);
        when(tickets.getTicketTypeById("slow")).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return null;
        });
        long started = System.nanoTime();
        assertThatCode(() -> quick.validate(order("slow", 99))).doesNotThrowAnyException();
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void createBookingStopsBeforeTouchingAnythingWhenTheOrderIsOutOfRange() {
        BookingRepository bookings = mock(BookingRepository.class);
        GaReservationService ga = mock(GaReservationService.class);
        when(ga.plan(any())).thenReturn(new GaReservationService.Plan(List.of(), null));
        BookingServiceImpl service = new BookingServiceImpl(bookings, mock(SeatReservationRepository.class),
                mock(OutboxEventRepository.class), mock(RedissonClient.class), mock(StringRedisTemplate.class), new ObjectMapper(),
                mock(EventServiceClient.class), mock(TicketServiceClient.class), ga, validator, new OrderShapeValidator());
        BookingCreateRequest request = order("t1", 9);
        request.setEventId("e1");
        assertThatThrownBy(() -> service.createBooking(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getReason()).isEqualTo("QUANTITY_OUT_OF_RANGE"));
        verify(bookings, never()).save(any());
    }
}
