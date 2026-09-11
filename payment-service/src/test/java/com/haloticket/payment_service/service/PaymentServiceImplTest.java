package com.haloticket.payment_service.service;

import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.entity.OrganizerPayoutAccount;
import com.haloticket.payment_service.enums.PayoutAccountVerificationStatus;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.repository.PaymentRepository;
import com.haloticket.payment_service.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock PaymentRepository paymentRepository;
    @Mock OrganizerPayoutAccountRepository accountRepository;

    @Test
    void newPayoutAccountStartsUnverified() {
        PaymentServiceImpl service = new PaymentServiceImpl(paymentRepository, accountRepository);
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
        PaymentServiceImpl service = new PaymentServiceImpl(paymentRepository, accountRepository);
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
        PaymentServiceImpl service = new PaymentServiceImpl(paymentRepository, accountRepository);
        when(accountRepository.findByOrganizerUserId("user-1")).thenReturn(Optional.of(existing));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.saveOrUpdatePayoutAccount(request("123456789", "NGUYEN VAN A"));

        assertThat(response.getVerificationStatus()).isEqualTo(PayoutAccountVerificationStatus.UNVERIFIED);
        assertThat(response.getIsVerified()).isFalse();
        assertThat(existing.getVerificationReference()).isNull();
        assertThat(existing.getVerifiedAt()).isNull();
        assertThat(existing.getAccountVersion()).isEqualTo(4L);
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
