package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.client.UserServiceClient;
import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {
    @Mock PaymentService paymentService;
    @Mock UserServiceClient userServiceClient;
    @Mock com.haloticket.payment_service.service.VNPayService vnPayService;
    @Mock com.haloticket.payment_service.config.SePayConfig sePayConfig;

    @Test
    void payoutAccountAlwaysUsesAuthenticatedInternalUserId() {
        PaymentController controller = new PaymentController(paymentService, userServiceClient, vnPayService, sePayConfig);
        OrganizerPayoutAccountRequest request = new OrganizerPayoutAccountRequest();
        request.setOrganizerUserId("spoofed-user");
        request.setAccountNumber("123456789");
        when(userServiceClient.getOrganizerEligibility("Bearer token"))
                .thenReturn(new OrganizerEligibilityResponse(true, "real-user", 2L, "ELIGIBLE"));

        controller.saveOrUpdatePayoutAccount("Bearer token", request);

        ArgumentCaptor<OrganizerPayoutAccountRequest> captor =
                ArgumentCaptor.forClass(OrganizerPayoutAccountRequest.class);
        verify(paymentService).saveOrUpdatePayoutAccount(captor.capture());
        assertThat(captor.getValue().getOrganizerUserId()).isEqualTo("real-user");
        assertThat(captor.getValue().getIdentityVersion()).isEqualTo(2L);
    }
}
