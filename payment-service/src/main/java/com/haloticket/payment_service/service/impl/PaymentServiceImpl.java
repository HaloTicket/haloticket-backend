package com.haloticket.payment_service.service.impl;

import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;
import com.haloticket.payment_service.entity.OrganizerPayoutAccount;
import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.enums.PaymentStatus;
import com.haloticket.payment_service.enums.PayoutAccountVerificationStatus;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.repository.PaymentRepository;
import com.haloticket.payment_service.service.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.payment_service.entity.OutboxEvent;
import com.haloticket.payment_service.enums.OutboxStatus;
import com.haloticket.payment_service.repository.OutboxEventRepository;
import com.haloticket.payment_service.service.VNPayService;
import lombok.extern.slf4j.Slf4j;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrganizerPayoutAccountRepository organizerPayoutAccountRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final VNPayService vnPayService;
    private final ObjectMapper objectMapper;

    public PaymentServiceImpl(PaymentRepository paymentRepository,
                               OrganizerPayoutAccountRepository organizerPayoutAccountRepository,
                               OutboxEventRepository outboxEventRepository,
                               VNPayService vnPayService,
                               ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.organizerPayoutAccountRepository = organizerPayoutAccountRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.vnPayService = vnPayService;
        this.objectMapper = objectMapper;
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
                        .isVerified(false)
                        .verificationStatus(PayoutAccountVerificationStatus.UNVERIFIED)
                        .accountVersion(0L)
                        .build());

        boolean bankIdentityChanged = differs(request.getAccountHolder(), account.getAccountHolder())
                || differs(request.getAccountNumber(), account.getAccountNumber())
                || differs(request.getBankName(), account.getBankName())
                || !Objects.equals(request.getIdentityVersion(), account.getIdentityVersion());

        if (request.getAccountHolder() != null) account.setAccountHolder(request.getAccountHolder());
        if (request.getAccountNumber() != null) account.setAccountNumber(request.getAccountNumber());
        if (request.getBankName() != null) account.setBankName(request.getBankName());
        if (request.getBankBranch() != null) account.setBankBranch(request.getBankBranch());
        if (request.getBusinessType() != null) account.setBusinessType(request.getBusinessType());
        if (request.getTaxName() != null) account.setTaxName(request.getTaxName());
        if (request.getTaxCode() != null) account.setTaxCode(request.getTaxCode());
        if (request.getTaxAddress() != null) account.setTaxAddress(request.getTaxAddress());

        if (bankIdentityChanged) {
            account.setIsVerified(false);
            account.setVerificationStatus(PayoutAccountVerificationStatus.UNVERIFIED);
            account.setVerificationReference(null);
            account.setVerifiedAt(null);
            account.setAccountVersion((account.getAccountVersion() == null ? 0L : account.getAccountVersion()) + 1L);
        }
        account.setIdentityVersion(request.getIdentityVersion());

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
                .isVerified(Boolean.TRUE.equals(entity.getIsVerified())
                        && entity.getVerificationStatus() == PayoutAccountVerificationStatus.VERIFIED
                        && entity.getVerificationReference() != null && entity.getVerifiedAt() != null
                        && entity.getIdentityVersion() != null)
                .verificationStatus(entity.getVerificationStatus())
                .accountVersion(entity.getAccountVersion())
                .identityVersion(entity.getIdentityVersion())
                .build();
    }

    @Override
    @Transactional
    public Map<String, String> processVnPayIpn(Map<String, String> allParams) {
        Map<String, String> response = new HashMap<>();
        boolean isValidChecksum = vnPayService.verifyIpn(allParams);
        if (!isValidChecksum) {
            response.put("RspCode", "97");
            response.put("Message", "Invalid Checksum");
            return response;
        }

        String bookingId = allParams.get("vnp_TxnRef");
        String vnp_ResponseCode = allParams.get("vnp_ResponseCode");
        String amountStr = allParams.get("vnp_Amount");

        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseGet(() -> Payment.builder()
                        .bookingId(bookingId)
                        .amount(amountStr != null ? Double.parseDouble(amountStr) / 100.0 : 0.0)
                        .status(PaymentStatus.PENDING)
                        .build());

        if ("00".equals(vnp_ResponseCode)) {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);

            // Outbox event: PAYMENT_COMPLETED
            try {
                Map<String, Object> payloadMap = new HashMap<>();
                payloadMap.put("paymentId", payment.getId());
                payloadMap.put("bookingId", bookingId);
                payloadMap.put("amount", payment.getAmount());
                payloadMap.put("txnRef", bookingId);
                payloadMap.put("vnpayResponseCode", vnp_ResponseCode);

                OutboxEvent outboxEvent = OutboxEvent.builder()
                        .aggregateType("PAYMENT")
                        .aggregateId(payment.getId())
                        .eventType("PAYMENT_COMPLETED")
                        .topic("haloticket.payment.completed.v1")
                        .payload(objectMapper.writeValueAsString(payloadMap))
                        .status(OutboxStatus.PENDING)
                        .retryCount(0)
                        .build();

                outboxEventRepository.save(outboxEvent);
            } catch (Exception e) {
                log.error("Failed to write OutboxEvent for payment success", e);
            }

            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);

            // Outbox event: PAYMENT_FAILED
            try {
                Map<String, Object> payloadMap = new HashMap<>();
                payloadMap.put("paymentId", payment.getId());
                payloadMap.put("bookingId", bookingId);
                payloadMap.put("reason", "VNPay error code " + vnp_ResponseCode);

                OutboxEvent outboxEvent = OutboxEvent.builder()
                        .aggregateType("PAYMENT")
                        .aggregateId(payment.getId())
                        .eventType("PAYMENT_FAILED")
                        .topic("haloticket.payment.failed.v1")
                        .payload(objectMapper.writeValueAsString(payloadMap))
                        .status(OutboxStatus.PENDING)
                        .retryCount(0)
                        .build();

                outboxEventRepository.save(outboxEvent);
            } catch (Exception e) {
                log.error("Failed to write OutboxEvent for payment failure", e);
            }

            response.put("RspCode", "01");
            response.put("Message", "Transaction Failed");
        }

        return response;
    }

    private boolean differs(String incoming, String existing) {
        return incoming != null && !Objects.equals(incoming.trim(), existing == null ? null : existing.trim());
    }
}
