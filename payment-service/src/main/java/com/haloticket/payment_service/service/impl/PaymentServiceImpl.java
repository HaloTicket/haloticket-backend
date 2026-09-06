package com.haloticket.payment_service.service.impl;

import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;
import com.haloticket.payment_service.entity.OrganizerPayoutAccount;
import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.enums.PaymentStatus;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.repository.PaymentRepository;
import com.haloticket.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrganizerPayoutAccountRepository organizerPayoutAccountRepository;

    public PaymentServiceImpl(PaymentRepository paymentRepository,
                              OrganizerPayoutAccountRepository organizerPayoutAccountRepository) {
        this.paymentRepository = paymentRepository;
        this.organizerPayoutAccountRepository = organizerPayoutAccountRepository;
    }

    @Override
    @Transactional
    public PaymentResponse createPayment(PaymentCreateRequest request) {
        if (request == null) return null;
        Payment payment = Payment.builder()
                .bookingId(request.getBookingId())
                .amount(request.getAmount())
                .method(request.getMethod())
                .provider(request.getProvider())
                .status(PaymentStatus.PENDING)
                .build();
        Payment saved = paymentRepository.save(payment);
        return PaymentResponse.builder()
                .id(saved.getId())
                .bookingId(saved.getBookingId())
                .amount(saved.getAmount())
                .status(saved.getStatus())
                .build();
    }

    @Override
    @Transactional
    public OrganizerPayoutAccountResponse saveOrUpdatePayoutAccount(OrganizerPayoutAccountRequest request) {
        if (request == null || request.getOrganizerUserId() == null) return null;

        OrganizerPayoutAccount account = organizerPayoutAccountRepository
                .findByOrganizerUserId(request.getOrganizerUserId())
                .orElseGet(() -> OrganizerPayoutAccount.builder()
                        .organizerUserId(request.getOrganizerUserId())
                        .build());

        if (request.getAccountHolder() != null) account.setAccountHolder(request.getAccountHolder());
        if (request.getAccountNumber() != null) account.setAccountNumber(request.getAccountNumber());
        if (request.getBankName() != null) account.setBankName(request.getBankName());
        if (request.getBankBranch() != null) account.setBankBranch(request.getBankBranch());
        if (request.getBusinessType() != null) account.setBusinessType(request.getBusinessType());
        if (request.getTaxName() != null) account.setTaxName(request.getTaxName());
        if (request.getTaxCode() != null) account.setTaxCode(request.getTaxCode());
        if (request.getTaxAddress() != null) account.setTaxAddress(request.getTaxAddress());

        OrganizerPayoutAccount saved = organizerPayoutAccountRepository.save(account);
        return mapToPayoutResponse(saved);
    }

    @Override
    public OrganizerPayoutAccountResponse getPayoutAccountByUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) return null;
        return organizerPayoutAccountRepository.findByOrganizerUserId(userId)
                .map(this::mapToPayoutResponse)
                .orElse(null);
    }

    private OrganizerPayoutAccountResponse mapToPayoutResponse(OrganizerPayoutAccount entity) {
        if (entity == null) return null;
        return OrganizerPayoutAccountResponse.builder()
                .id(entity.getId())
                .organizerUserId(entity.getOrganizerUserId())
                .accountHolder(entity.getAccountHolder())
                .accountNumber(entity.getAccountNumber())
                .bankName(entity.getBankName())
                .bankBranch(entity.getBankBranch())
                .businessType(entity.getBusinessType())
                .taxName(entity.getTaxName())
                .taxCode(entity.getTaxCode())
                .taxAddress(entity.getTaxAddress())
                .isVerified(entity.getIsVerified())
                .build();
    }
}