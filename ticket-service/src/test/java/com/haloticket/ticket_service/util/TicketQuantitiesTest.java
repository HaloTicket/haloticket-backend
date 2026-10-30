package com.haloticket.ticket_service.util;

import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.service.impl.TicketServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TicketQuantitiesTest {

    @Test
    void normalRowAddsUp() {
        // 100 put on sale, 30 sold, 10 held: 70 left in stock, 60 can be bought now
        assertThat(TicketQuantities.remaining(70)).isEqualTo(70);
        assertThat(TicketQuantities.held(10)).isEqualTo(10);
        assertThat(TicketQuantities.available(70, 10)).isEqualTo(60);
        assertThat(TicketQuantities.sold(100, 70)).isEqualTo(30);
    }

    @Test
    void oldRowWithoutInitialQuantityHasUnknownSoldNotZero() {
        assertThat(TicketQuantities.sold(null, 70)).isNull();
        assertThat(TicketQuantities.sold(100, null)).isNull();
    }

    @Test
    void nothingGoesNegativeWhenHeldExceedsTotalOrTotalExceedsInitial() {
        assertThat(TicketQuantities.available(5, 9)).isZero();
        assertThat(TicketQuantities.sold(100, 120)).isZero(); // organizer raised the stock after selling
        assertThat(TicketQuantities.held(-3)).isZero();
    }

    @Test
    void nullHeldCountsAsZeroAndNullTotalGivesNullAvailable() {
        assertThat(TicketQuantities.held(null)).isZero();
        assertThat(TicketQuantities.available(40, null)).isEqualTo(40);
        assertThat(TicketQuantities.available(null, 3)).isNull();
        assertThat(TicketQuantities.remaining(null)).isNull();
    }

    private TicketTypeResponse respond(Integer initial, Integer total, Integer reserved) {
        TicketServiceImpl service = Mockito.mock(TicketServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        TicketType entity = TicketType.builder().id("t1").initialQuantity(initial).totalQuantity(total).reservedQuantity(reserved).build();
        return ReflectionTestUtils.invokeMethod(service, "mapToResponse", entity);
    }

    @Test
    void responseKeepsTheOldFieldsAndAddsTheThreeNewOnes() {
        TicketTypeResponse r = respond(100, 70, 10);
        assertThat(r.getTotalQuantity()).isEqualTo(70);
        assertThat(r.getInitialQuantity()).isEqualTo(100);
        assertThat(r.getAvailableQuantity()).isEqualTo(60);
        assertThat(r.getRemainingQuantity()).isEqualTo(70);
        assertThat(r.getHeldQuantity()).isEqualTo(10);
        assertThat(r.getSoldQuantity()).isEqualTo(30);
    }

    @Test
    void responseForAnOldRowAndForHeldAboveTotal() {
        TicketTypeResponse old = respond(null, 70, null);
        assertThat(old.getSoldQuantity()).isNull();
        assertThat(old.getHeldQuantity()).isZero();
        assertThat(old.getAvailableQuantity()).isEqualTo(70);
        TicketTypeResponse over = respond(10, 5, 9);
        assertThat(over.getAvailableQuantity()).isZero();
        assertThat(over.getSoldQuantity()).isEqualTo(5);
    }
}
