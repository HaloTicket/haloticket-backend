package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.dto.response.OrganizerCashFlowResponse;
import com.haloticket.payment_service.service.PayoutService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutAnalyticsControllerTest {
    private final AtomicReference<LocalDate[]> forwardedRange = new AtomicReference<>();
    private final AtomicReference<String> forwardedEventId = new AtomicReference<>();

    @Test
    void missingDatesDefaultToLastThirtyCalendarDaysInclusive() {
        LocalDate today = LocalDate.now();

        controller().getOrganizerAnalytics("Bearer test", null, null, null);

        assertThat(forwardedRange.get()).containsExactly(today.minusDays(29), today);
    }

    @Test
    void missingFromUsesThirtyDaysEndingOnRequestedTo() {
        LocalDate to = LocalDate.of(2026, 10, 30);

        controller().getOrganizerAnalytics("Bearer test", null, null, to);

        assertThat(forwardedRange.get()).containsExactly(to.minusDays(29), to);
    }

    @Test
    void missingToUsesTodayAsEndDate() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate today = LocalDate.now();

        controller().getOrganizerAnalytics("Bearer test", null, from, null);

        assertThat(forwardedRange.get()).containsExactly(from, today);
    }

    @Test
    void forwardsOptionalEventFilter() {
        controller().getOrganizerAnalytics("Bearer test", null, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 30), "event-1");

        assertThat(forwardedEventId.get()).isEqualTo("event-1");
    }

    private PayoutController controller() {
        PayoutService payoutService = (PayoutService) Proxy.newProxyInstance(PayoutService.class.getClassLoader(),
                new Class<?>[]{PayoutService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "resolveOrganizerId" -> "organizer-1";
                    case "getOrganizerAnalytics" -> {
                        forwardedRange.set(new LocalDate[]{(LocalDate) args[2], (LocalDate) args[3]});
                        forwardedEventId.set(args.length > 4 ? (String) args[4] : null);
                        yield OrganizerCashFlowResponse.builder().build();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new PayoutController(payoutService, null, null, null, null);
    }
}
