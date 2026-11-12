package com.haloticket.event_service.service;

import com.haloticket.event_service.controller.VoucherController;
import com.haloticket.event_service.dto.response.VoucherResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.Voucher;
import com.haloticket.event_service.enums.VoucherStatus;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.VoucherRepository;
import com.haloticket.event_service.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Counting voucher uses, who may count them, and what a buyer can see of unreleased vouchers. */
@ExtendWith(MockitoExtension.class)
class VoucherUseTest {
    @Mock VoucherRepository voucherRepository;
    @Mock EventRepository eventRepository;
    @Mock EventAccessService access;

    VoucherServiceImpl service;

    @BeforeEach
    void wire() {
        service = new VoucherServiceImpl(voucherRepository, eventRepository);
    }

    private Voucher voucher(String id, String code, VoucherStatus status, String limitType, Integer limit, int used) {
        return Voucher.builder().id(id).code(code).status(status).applyTicketLimitType(limitType)
                .ticketLimitCount(limit).usedCount(used).event(Event.builder().id("ev1").build()).build();
    }

    @Test
    void aUseAddsTheTicketsToTheCounter() {
        Voucher v = voucher("v1", "HALO", VoucherStatus.ACTIVE, "LIMITED", 10, 2);
        when(voucherRepository.findByIdForUpdate("v1")).thenReturn(Optional.of(v));
        when(voucherRepository.save(any(Voucher.class))).thenAnswer(call -> call.getArgument(0));

        VoucherResponse response = service.useVoucher("v1", 3);

        assertThat(response.getUsedCount()).isEqualTo(5);
        assertThat(v.getStatus()).isEqualTo(VoucherStatus.ACTIVE);
    }

    @Test
    void theVoucherSwitchesOffWhenTheLastTicketIsTaken() {
        Voucher v = voucher("v1", "HALO", VoucherStatus.ACTIVE, "LIMITED", 10, 8);
        when(voucherRepository.findByIdForUpdate("v1")).thenReturn(Optional.of(v));
        when(voucherRepository.save(any(Voucher.class))).thenAnswer(call -> call.getArgument(0));

        service.useVoucher("v1", 2);

        assertThat(v.getUsedCount()).isEqualTo(10);
        assertThat(v.getStatus()).isEqualTo(VoucherStatus.INACTIVE);
    }

    @Test
    void anUnlimitedVoucherKeepsCountingAndStaysActive() {
        Voucher v = voucher("v1", "HALO", VoucherStatus.ACTIVE, "UNLIMITED", 99999, 500);
        when(voucherRepository.findByIdForUpdate("v1")).thenReturn(Optional.of(v));
        when(voucherRepository.save(any(Voucher.class))).thenAnswer(call -> call.getArgument(0));

        service.useVoucher("v1", 4);

        assertThat(v.getUsedCount()).isEqualTo(504);
        assertThat(v.getStatus()).isEqualTo(VoucherStatus.ACTIVE);
    }

    @Test
    void aCountOutsideOneToAHundredIsRefusedWithoutTouchingTheVoucher() {
        for (int bad : new int[]{0, -5, 101}) {
            assertThatThrownBy(() -> service.useVoucher("v1", bad))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(voucherRepository);
    }

    @Test
    void anUnknownVoucherIsNotFound() {
        when(voucherRepository.findByIdForUpdate("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.useVoucher("nope", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aBuyerFindsAReleasedVoucherByItsCodeButNeverADraft() {
        when(voucherRepository.findByEventIdOrderByCreatedAtDesc("ev1")).thenReturn(List.of(
                voucher("v1", "SALE10", VoucherStatus.DRAFT, "UNLIMITED", null, 0),
                voucher("v2", "HALO50", VoucherStatus.ACTIVE, "UNLIMITED", null, 0)));

        assertThat(service.findReleasedByCode("ev1", "halo50")).map(VoucherResponse::getId).contains("v2");
        assertThat(service.findReleasedByCode("ev1", "SALE10")).isEmpty();
        assertThat(service.findReleasedByCode("ev1", " ")).isEmpty();
    }

    // --- the controller: only the other services may count a use; buyers do not see drafts or codes ---

    private VoucherController controller(String token) {
        VoucherController controller = new VoucherController(service, access);
        ReflectionTestUtils.setField(controller, "internalToken", token);
        return controller;
    }

    @Test
    void theUseEndpointNeedsTheSharedInternalToken() {
        VoucherController controller = controller("secret");

        for (String presented : new String[]{null, "", "wrong"}) {
            assertThatThrownBy(() -> controller.useVoucher("v1", 1, presented))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        verifyNoInteractions(voucherRepository);
    }

    @Test
    void withNoTokenConfiguredNoOneIsAccepted() {
        VoucherController controller = controller("");

        assertThatThrownBy(() -> controller.useVoucher("v1", 1, ""))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void theRightTokenCountsTheUse() {
        Voucher v = voucher("v1", "HALO", VoucherStatus.ACTIVE, "LIMITED", 10, 0);
        when(voucherRepository.findByIdForUpdate("v1")).thenReturn(Optional.of(v));
        when(voucherRepository.save(any(Voucher.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(controller("secret").useVoucher("v1", 2, "secret").getBody().getUsedCount()).isEqualTo(2);
    }

    @Test
    void thePublicListHidesDraftsAndCodesButTheManagerSeesEverything() {
        when(voucherRepository.findByEventIdOrderByCreatedAtDesc("ev1")).thenAnswer(call -> List.of(
                voucher("v1", "SALE10", VoucherStatus.DRAFT, "UNLIMITED", null, 0),
                voucher("v2", "HALO50", VoucherStatus.ACTIVE, "UNLIMITED", null, 0)));
        VoucherController controller = controller("secret");

        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireForRequest(eq("ev1"), any());
        List<VoucherResponse> asBuyer = controller.getVouchersByEventId("ev1").getBody();
        assertThat(asBuyer).extracting(VoucherResponse::getId).containsExactly("v2");
        assertThat(asBuyer.get(0).getCode()).isNull();

        reset(access);
        List<VoucherResponse> asManager = controller.getVouchersByEventId("ev1").getBody();
        assertThat(asManager).extracting(VoucherResponse::getId).containsExactly("v1", "v2");
        assertThat(asManager.get(1).getCode()).isEqualTo("HALO50");
    }
}
