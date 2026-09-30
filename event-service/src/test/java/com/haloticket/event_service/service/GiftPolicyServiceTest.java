package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.GiftPolicyRequest;
import com.haloticket.event_service.dto.response.GiftPolicyResponse;
import com.haloticket.event_service.entity.GiftPolicy;
import com.haloticket.event_service.repository.GiftPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GiftPolicyServiceTest {
    @Mock GiftPolicyRepository repository;
    @Mock EventAccessService access;
    @Mock TransferPolicySupport support;
    @InjectMocks GiftPolicyService service;

    private GiftPolicy savedPolicy(String eventId) {
        GiftPolicy p = GiftPolicy.builder().eventId(eventId).enabled(true)
                .invitationTtlHours(24).ticketTypeScope("ALL").allowedTicketTypeIds(Set.of()).version(0L).build();
        p.setCreatedAt(Instant.now()); p.setUpdatedAt(Instant.now());
        return p;
    }

    @Test void ownerCanUpsertNewGiftPolicy() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        when(repository.findByEventId("e1")).thenReturn(Optional.empty());
        GiftPolicy saved = savedPolicy("e1");
        when(repository.save(any())).thenReturn(saved);
        var req = new GiftPolicyRequest(true, null, null, 24, "ALL", Set.of(), null);
        GiftPolicyResponse res = service.upsert("e1", req, "Bearer token");
        assertThat(res.enabled()).isTrue();
        assertThat(res.invitationTtlHours()).isEqualTo(24);
        verify(support).record(eq("e1"), eq("GIFT"), eq("user-1"), isNull(), any(), eq(0L));
    }

    @Test void ttlZeroRejects422() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        when(repository.findByEventId("e1")).thenReturn(Optional.empty());
        var req = new GiftPolicyRequest(true, null, null, 0, "ALL", Set.of(), null);
        assertThatThrownBy(() -> service.upsert("e1", req, "Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test void ttlOver168Rejects422() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        when(repository.findByEventId("e1")).thenReturn(Optional.empty());
        var req = new GiftPolicyRequest(true, null, null, 200, "ALL", Set.of(), null);
        assertThatThrownBy(() -> service.upsert("e1", req, "Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test void versionMismatchRejects409() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        GiftPolicy existing = savedPolicy("e1");
        existing.setVersion(3L);
        when(repository.findByEventId("e1")).thenReturn(Optional.of(existing));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "POLICY_CHANGED"))
                .when(support).version(1L, 3L);
        var req = new GiftPolicyRequest(true, null, null, 24, "ALL", Set.of(), 1L);
        assertThatThrownBy(() -> service.upsert("e1", req, "Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }
}
