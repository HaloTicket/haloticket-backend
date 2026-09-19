package com.haloticket.payment_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.entity.OrganizerPayoutAccount;
import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.enums.PaymentMethod;
import com.haloticket.payment_service.enums.PaymentProvider;
import com.haloticket.payment_service.enums.PaymentStatus;
import com.haloticket.payment_service.enums.PayoutAccountVerificationStatus;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.repository.OutboxEventRepository;
import com.haloticket.payment_service.repository.PaymentRepository;
import com.haloticket.payment_service.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock PaymentRepository paymentRepository;
    @Mock OrganizerPayoutAccountRepository accountRepository;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock VNPayService vnPayService;
    @Mock SePayService sePayService;
    @Mock MoMoService moMoService;
    @Mock ZaloPayService zaloPayService;
    @Mock ShopeePayService shopeePayService;
    @Mock ObjectMapper objectMapper;

    private PaymentServiceImpl createService() {
        return new PaymentServiceImpl(
                paymentRepository,
                accountRepository,
                outboxEventRepository,
                vnPayService,
                sePayService,
                moMoService,
                zaloPayService,
                shopeePayService,
                objectMapper
        );
    }

    @Test
    void newPayoutAccountStartsUnverified() {
        PaymentServiceImpl service = createService();
        OrganizerPayoutAccountRequest request = request("123456789", "NGUYEN VAN A");
        when(accountRepository.findByOrganizerUserId("user-1")).thenReturn(Optional.empty());
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.saveOrUpdatePayoutAccount(request);

        assertThat(response.getVerificationStatus()).isEqualTo(PayoutAccountVerificationStatus.UNVERIFIED);
        assertThat(response.getIsVerified()).isFalse();
    }

    @Test
    void changingBankIdentityRevokesPreviousVerificationAndIncrementsVersion() {
        OrganizerPayoutAccount existing = OrganizerPayoutAccount.builder()
                .organizerUserId("user-1")
                .accountHolder("NGUYEN VAN A")
                .accountNumber("111111111")
                .bankName("VCB")
                .verificationStatus(PayoutAccountVerificationStatus.VERIFIED)
                .isVerified(true)
                .accountVersion(3L)
                .build();
        PaymentServiceImpl service = createService();
        when(accountRepository.findByOrganizerUserId("user-1")).thenReturn(Optional.of(existing));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.saveOrUpdatePayoutAccount(request("222222222", "NGUYEN VAN A"));

        ArgumentCaptor<OrganizerPayoutAccount> captor = ArgumentCaptor.forClass(OrganizerPayoutAccount.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getVerificationStatus()).isEqualTo(PayoutAccountVerificationStatus.UNVERIFIED);
        assertThat(captor.getValue().getIsVerified()).isFalse();
        assertThat(captor.getValue().getAccountVersion()).isEqualTo(4L);
    }

    @Test
    void changingEkycIdentityVersionRevokesPreviousBankVerification() {
        OrganizerPayoutAccount existing = OrganizerPayoutAccount.builder()
                .organizerUserId("user-1")
                .identityVersion(1L)
                .accountHolder("NGUYEN VAN A")
                .accountNumber("123456789")
                .bankName("VCB")
                .verificationStatus(PayoutAccountVerificationStatus.VERIFIED)
                .verificationReference("bank-check-1")
                .verifiedAt(LocalDateTime.now())
                .isVerified(true)
                .accountVersion(3L)
                .build();
        PaymentServiceImpl service = createService();
        when(accountRepository.findByOrganizerUserId("user-1")).thenReturn(Optional.of(existing));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.saveOrUpdatePayoutAccount(request("123456789", "NGUYEN VAN A"));

        assertThat(response.getVerificationStatus()).isEqualTo(PayoutAccountVerificationStatus.UNVERIFIED);
        assertThat(response.getIsVerified()).isFalse();
        assertThat(existing.getVerificationReference()).isNull();
        assertThat(existing.getVerifiedAt()).isNull();
        assertThat(existing.getAccountVersion()).isEqualTo(4L);
    }

    @Test
    void sandboxReturnConfirmsPendingPaymentAndPublishesCompletionOnce() throws Exception {
        Payment payment = Payment.builder()
                .id("payment-1")
                .bookingId("transaction-1")
                .amount(320000D)
                .method(PaymentMethod.MOMO)
                .provider(PaymentProvider.MOMO_GATEWAY)
                .status(PaymentStatus.PENDING)
                .build();
        when(paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc("transaction-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        PaymentServiceImpl service = createService();

        var first = service.confirmSandboxReturn("transaction-1", "momo");
        var second = service.confirmSandboxReturn("transaction-1", "momo");

        assertThat(first.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(second.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(outboxEventRepository, times(1)).save(any());
    }

    private OrganizerPayoutAccountRequest request(String number, String holder) {
        OrganizerPayoutAccountRequest request = new OrganizerPayoutAccountRequest();
        request.setOrganizerUserId("user-1");
        request.setIdentityVersion(2L);
        request.setAccountHolder(holder);
        request.setAccountNumber(number);
        request.setBankName("VCB");
        return request;
    }
}
