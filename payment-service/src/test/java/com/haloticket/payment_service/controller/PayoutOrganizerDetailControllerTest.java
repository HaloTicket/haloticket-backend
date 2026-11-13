package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.client.EventServiceClient;
import com.haloticket.payment_service.dto.response.EventFinanceSource;
import com.haloticket.payment_service.dto.response.PayoutResponse;
import com.haloticket.payment_service.service.PayoutService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutOrganizerDetailControllerTest {
    @Test
    void returnsOwnedPayoutDetailWhenEventNameEnrichmentIsUnavailable() {
        String payoutId = "fab069ec-5aa2-46de-a11c-cca22905b310";
        PayoutResponse payout = PayoutResponse.builder()
                .id(payoutId)
                .eventId("f3a1e4c2-7d65-4f90-9a20-000000000101")
                .status(com.haloticket.payment_service.enums.PayoutStatus.PENDING)
                .build();
        PayoutService service = (PayoutService) Proxy.newProxyInstance(PayoutService.class.getClassLoader(),
                new Class<?>[]{PayoutService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "resolveOrganizerId" -> "organizer-1";
                    case "getOrganizerPayout" -> payout;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        EventServiceClient eventClient = (EventServiceClient) Proxy.newProxyInstance(EventServiceClient.class.getClassLoader(),
                new Class<?>[]{EventServiceClient.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("event service unavailable");
                });
        PayoutController controller = new PayoutController(service, null, null, null, eventClient);

        var response = controller.getOrganizerDetail("Bearer organizer-token", payoutId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(payout);
        assertThat(response.getBody().getId()).isEqualTo(payoutId);
        assertThat(response.getBody().getStatus()).isEqualTo(com.haloticket.payment_service.enums.PayoutStatus.PENDING);
        assertThat(response.getBody().getEventName()).isNull();
    }

    @Test
    void enrichesEventNameAndEndTimeFromAuthenticatedOrganizerEvents() {
        PayoutResponse payout = PayoutResponse.builder().id("payout-1").eventId("event-1")
                .status(com.haloticket.payment_service.enums.PayoutStatus.PENDING).build();
        PayoutService service = (PayoutService) Proxy.newProxyInstance(PayoutService.class.getClassLoader(),
                new Class<?>[]{PayoutService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "resolveOrganizerId" -> "organizer-1";
                    case "getOrganizerPayout" -> payout;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        LocalDateTime endAt = LocalDateTime.of(2026, 10, 20, 22, 0);
        EventFinanceSource ownedEvent = new EventFinanceSource();
        ownedEvent.setId("event-1"); ownedEvent.setTitle("Owned event"); ownedEvent.setEndTime(endAt);
        EventServiceClient eventClient = (EventServiceClient) Proxy.newProxyInstance(EventServiceClient.class.getClassLoader(),
                new Class<?>[]{EventServiceClient.class}, (proxy, method, args) -> List.of(ownedEvent));

        var response = new PayoutController(service, null, null, null, eventClient)
                .getOrganizerDetail("Bearer organizer-token", "payout-1");

        assertThat(response.getBody().getEventName()).isEqualTo("Owned event");
        assertThat(response.getBody().getEventEndAt()).isEqualTo(endAt);
    }
}
