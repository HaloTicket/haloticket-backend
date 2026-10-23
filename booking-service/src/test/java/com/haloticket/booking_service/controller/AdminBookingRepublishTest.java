package com.haloticket.booking_service.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.config.BookingJwtRoleConverter;
import com.haloticket.booking_service.config.InternalReportSecurityConfig;
import com.haloticket.booking_service.exception.GlobalExceptionHandler;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminBookingController.class, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@Import({InternalReportSecurityConfig.class, BookingJwtRoleConverter.class, GlobalExceptionHandler.class})
class AdminBookingRepublishTest {
    private static final String TOPIC = "haloticket.booking.confirmed.v1";
    private static final String URL = "/api/bookings/admin/{id}/republish-confirmed";

    @Autowired MockMvc mvc;
    @MockitoBean BookingRepository bookingRepository;
    @MockitoBean OutboxEventRepository outboxEventRepository;
    @MockitoBean SeatReservationRepository seatReservationRepository;
    @MockitoBean EventServiceClient eventServiceClient;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean JwtDecoder jwtDecoder;

    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class WebSecurityEnabled {}

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(AdminBookingController.class)).addAppender(logs);
        when(jwtDecoder.decode("admin")).thenReturn(token("admin", "admin@test.local", "ROLE_ADMIN"));
        when(jwtDecoder.decode("customer")).thenReturn(token("customer", "customer@test.local", "ROLE_CUSTOMER"));
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(AdminBookingController.class)).detachAppender(logs);
    }

    /** Only send(...) calls count: Spring itself touches the mock during startup. */
    private List<org.mockito.invocation.Invocation> sendCalls() {
        return org.mockito.Mockito.mockingDetails(kafkaTemplate).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("send")).toList();
    }

    private void givenBooking(BookingStatus status) {
        Booking booking = Booking.builder().id("b-1").bookingCode("HALO-1").customerId("c-1")
                .eventId("e-1").showtimeId("s-1").totalAmount(100000d).status(status).build();
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking));
        when(outboxEventRepository.findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(seatReservationRepository.findByBooking(any())).thenReturn(List.of());
    }

    @Test
    void dryRunIsDefaultAndDoesNotSendToKafka() throws Exception {
        givenBooking(BookingStatus.PAID);

        mvc.perform(post(URL, "b-1").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.bookingId").value("b-1"));

        assertThat(sendCalls()).isEmpty();
    }

    @Test
    void nonAdminGetsForbiddenAndNothingIsRead() throws Exception {
        mvc.perform(post(URL, "b-1").param("dryRun", "false").header("Authorization", "Bearer customer"))
                .andExpect(status().isForbidden());

        assertThat(sendCalls()).isEmpty();
        verify(bookingRepository, never()).findById(any());
    }

    @Test
    void anonymousGetsUnauthorized() throws Exception {
        mvc.perform(post(URL, "b-1").param("dryRun", "false")).andExpect(status().isUnauthorized());
        assertThat(sendCalls()).isEmpty();
    }

    @Test
    void bookingNotPaidOrConfirmedIsRejectedWithConflict() throws Exception {
        for (BookingStatus status : List.of(BookingStatus.PENDING, BookingStatus.CANCELLED, BookingStatus.EXPIRED, BookingStatus.REFUNDED)) {
            givenBooking(status);
            mvc.perform(post(URL, "b-1").param("dryRun", "false").header("Authorization", "Bearer admin"))
                    .andExpect(status().isConflict());
        }
        assertThat(sendCalls()).isEmpty();
    }

    @Test
    void confirmedBookingCanBeRepublished() throws Exception {
        givenBooking(BookingStatus.CONFIRMED);

        mvc.perform(post(URL, "b-1").param("dryRun", "false").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk());

        verify(kafkaTemplate).send(eq(TOPIC), eq("b-1"), anyString());
    }

    @Test
    void dryRunFalseSendsExactlyOneMessageWithBookingPayload() throws Exception {
        givenBooking(BookingStatus.PAID);

        mvc.perform(post(URL, "b-1").param("dryRun", "false").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false));

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("b-1"), payload.capture());
        assertThat(sendCalls()).hasSize(1);
        assertThat(payload.getValue()).contains("\"bookingId\":\"b-1\"").contains("\"eventId\":\"e-1\"");
    }

    @Test
    void auditLogRecordsCallerAndSuccess() throws Exception {
        givenBooking(BookingStatus.PAID);

        mvc.perform(post(URL, "b-1").param("dryRun", "false").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk());

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains("[ADMIN_AUDIT]") && m.contains("requested republish-confirmed")
                && m.contains("bookingId=b-1") && m.contains("dryRun=false"));
        assertThat(messages).anyMatch(m -> m.contains("[ADMIN_AUDIT] SUCCESS") && m.contains("bookingId=b-1"));
    }

    private static Jwt token(String value, String email, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(value).header("alg", "none").issuer("https://issuer.example")
                .subject("test-user").claim("email", email).issuedAt(now).expiresAt(now.plusSeconds(60))
                .claim("realm_access", Map.of("roles", List.of(role))).build();
    }
}
