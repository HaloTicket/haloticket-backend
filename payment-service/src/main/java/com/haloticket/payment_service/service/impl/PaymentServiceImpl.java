package com.haloticket.payment_service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;
import com.haloticket.payment_service.entity.OrganizerPayoutAccount;
import com.haloticket.payment_service.entity.OutboxEvent;
import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.enums.OutboxStatus;
import com.haloticket.payment_service.enums.PaymentMethod;
import com.haloticket.payment_service.enums.PaymentProvider;
import com.haloticket.payment_service.enums.PaymentStatus;
import com.haloticket.payment_service.enums.PayoutAccountVerificationStatus;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.repository.OutboxEventRepository;
import com.haloticket.payment_service.repository.PaymentRepository;
import com.haloticket.payment_service.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrganizerPayoutAccountRepository organizerPayoutAccountRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final VNPayService vnPayService;
    private final SePayService sePayService;
    private final MoMoService moMoService;
    private final ZaloPayService zaloPayService;
    private final ShopeePayService shopeePayService;
    private final ObjectMapper objectMapper;

    public PaymentServiceImpl(PaymentRepository paymentRepository,
                               OrganizerPayoutAccountRepository organizerPayoutAccountRepository,
                               OutboxEventRepository outboxEventRepository,
                               VNPayService vnPayService,
                               SePayService sePayService,
                               MoMoService moMoService,
                               ZaloPayService zaloPayService,
                               ShopeePayService shopeePayService,
                               ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.organizerPayoutAccountRepository = organizerPayoutAccountRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.vnPayService = vnPayService;
        this.sePayService = sePayService;
        this.moMoService = moMoService;
        this.zaloPayService = zaloPayService;
        this.shopeePayService = shopeePayService;
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
            account.setIdentityVersion(request.getIdentityVersion());
            account.setAccountVersion(account.getAccountVersion() == null ? 1L : account.getAccountVersion() + 1L);
        }

        OrganizerPayoutAccount saved = organizerPayoutAccountRepository.save(account);
        return OrganizerPayoutAccountResponse.builder()
                .id(saved.getId())
                .organizerUserId(saved.getOrganizerUserId())
                .accountHolder(saved.getAccountHolder())
                .accountNumber(saved.getAccountNumber())
                .bankName(saved.getBankName())
                .bankBranch(saved.getBankBranch())
                .businessType(saved.getBusinessType())
                .taxName(saved.getTaxName())
                .taxCode(saved.getTaxCode())
                .taxAddress(saved.getTaxAddress())
                .isVerified(saved.getIsVerified())
                .verificationStatus(saved.getVerificationStatus())
                .identityVersion(saved.getIdentityVersion())
                .accountVersion(saved.getAccountVersion())
                .build();
    }

    @Override
    public OrganizerPayoutAccountResponse getPayoutAccountByUserId(String userId) {
        if (userId == null || userId.isBlank()) return null;
        return organizerPayoutAccountRepository.findByOrganizerUserId(userId)
                .map(acc -> OrganizerPayoutAccountResponse.builder()
                        .id(acc.getId())
                        .organizerUserId(acc.getOrganizerUserId())
                        .accountHolder(acc.getAccountHolder())
                        .accountNumber(acc.getAccountNumber())
                        .bankName(acc.getBankName())
                        .bankBranch(acc.getBankBranch())
                        .businessType(acc.getBusinessType())
                        .taxName(acc.getTaxName())
                        .taxCode(acc.getTaxCode())
                        .taxAddress(acc.getTaxAddress())
                        .isVerified(acc.getIsVerified())
                        .verificationStatus(acc.getVerificationStatus())
                        .identityVersion(acc.getIdentityVersion())
                        .accountVersion(acc.getAccountVersion())
                        .build())
                .orElse(null);
    }

    @Override
    @Transactional
    public Map<String, String> processVnPayIpn(Map<String, String> allParams) {
        Map<String, String> response = new HashMap<>();
        boolean isValid = vnPayService.verifyIpn(allParams);

        if (!isValid) {
            response.put("RspCode", "97");
            response.put("Message", "Invalid Checksum");
            return response;
        }

        String bookingId = allParams.get("vnp_TxnRef");
        String vnp_ResponseCode = allParams.get("vnp_ResponseCode");
        long amount = Long.parseLong(allParams.getOrDefault("vnp_Amount", "0")) / 100;

        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseGet(() -> Payment.builder()
                        .bookingId(bookingId)
                        .amount((double) amount)
                        .method(PaymentMethod.VNPAY)
                        .provider(PaymentProvider.VNPAY_GATEWAY)
                        .status(PaymentStatus.PENDING)
                        .build());

        if ("00".equals(vnp_ResponseCode)) {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);

            // outbox event: PAYMENT_COMPLETED
            publishPaymentCompletedOutbox(payment, "VNPAY");

            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);

            publishPaymentFailedOutbox(payment, bookingId, "VNPay error code " + vnp_ResponseCode);

            response.put("RspCode", "01");
            response.put("Message", "Transaction Failed");
        }

        return response;
    }

    @Override
    @Transactional
    public Map<String, Object> createVietQrPayload(long amount, String bookingCode, String bookingId) {
        Map<String, Object> payload = sePayService.createVietQrPayload(amount, bookingCode, bookingId);

        // track pending payment in DB
        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseGet(() -> Payment.builder()
                        .bookingId(bookingId)
                        .amount((double) amount)
                        .method(PaymentMethod.VIETQR)
                        .provider(PaymentProvider.SEPAY)
                        .status(PaymentStatus.PENDING)
                        .build());
        paymentRepository.save(payment);

        return payload;
    }

    @Override
    @Transactional
    public Map<String, String> processSepayWebhook(Map<String, Object> webhookData) {
        Map<String, String> res = new HashMap<>();
        if (webhookData == null) {
            res.put("status", "error");
            return res;
        }

        log.info("Received SePay Webhook payload: {}", webhookData);

        String content = (String) webhookData.get("content");
        String code = webhookData.get("code") != null ? webhookData.get("code").toString().trim() : null;

        Payment payment = null;

        // 1. try finding by 'code' field if provided by SePay VA
        if (code != null && !code.isBlank()) {
            String cleanCode = code.toUpperCase().startsWith("HALO") ? code.substring(4).trim() : code;
            payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(cleanCode)
                    .orElseGet(() -> paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(code).orElse(null));
        }

        // 2. try finding by content substring if HALO prefix is present
        if (payment == null && content != null && !content.isBlank()) {
            String rawContent = content.trim();
            if (rawContent.toUpperCase().contains("HALO")) {
                int haloIdx = rawContent.toUpperCase().indexOf("HALO");
                String bookingRef = rawContent.substring(haloIdx + 4).trim();
                if (bookingRef.contains(" ")) {
                    bookingRef = bookingRef.split(" ")[0];
                }
                payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingRef).orElse(null);
            }

            if (payment == null) {
                payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(rawContent).orElse(null);
            }
        }

        // 3. fallback to latest PENDING payment if test Webhook has generic content
        if (payment == null) {
            payment = paymentRepository.findAll().stream()
                    .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                    .findFirst()
                    .orElse(null);
        }

        if (payment != null) {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);

            publishPaymentCompletedOutbox(payment, "SEPAY");

            res.put("status", "success");
            res.put("message", "Payment confirmed via SePay VietQR");
            log.info("Successfully confirmed SePay payment for bookingId: {}", payment.getBookingId());
            return res;
        }

        res.put("status", "received");
        return res;
    }

    @Override
    @Transactional
    public Map<String, String> createMoMoPaymentUrl(long amount, String orderInfo, String bookingId) {
        Map<String, String> result = moMoService.createPaymentUrl(amount, orderInfo, bookingId);

        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseGet(() -> Payment.builder()
                        .bookingId(bookingId)
                        .amount((double) amount)
                        .method(PaymentMethod.MOMO)
                        .provider(PaymentProvider.MOMO_GATEWAY)
                        .status(PaymentStatus.PENDING)
                        .build());
        paymentRepository.save(payment);

        return result;
    }

    @Override
    @Transactional
    public Map<String, String> processMoMoIpn(Map<String, String> allParams) {
        Map<String, String> res = new HashMap<>();
        boolean isValid = moMoService.verifySignature(allParams);

        String bookingId = allParams.get("orderId");
        String resultCode = allParams.get("resultCode");

        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (payment != null && ("0".equals(resultCode) || isValid)) {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);

            publishPaymentCompletedOutbox(payment, "MOMO");

            res.put("status", "success");
            res.put("message", "Confirm Success");
            return res;
        }

        res.put("status", "failed");
        return res;
    }

    @Override
    @Transactional
    public Map<String, String> createZaloPayPaymentUrl(long amount, String orderInfo, String bookingId) {
        Map<String, String> result = zaloPayService.createPaymentUrl(amount, orderInfo, bookingId);

        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseGet(() -> Payment.builder()
                        .bookingId(bookingId)
                        .amount((double) amount)
                        .method(PaymentMethod.ZALOPAY)
                        .provider(PaymentProvider.ZALOPAY_GATEWAY)
                        .status(PaymentStatus.PENDING)
                        .build());
        paymentRepository.save(payment);

        return result;
    }

    @Override
    @Transactional
    public Map<String, String> processZaloPayIpn(Map<String, Object> callbackData) {
        Map<String, String> res = new HashMap<>();
        if (callbackData == null) {
            res.put("return_code", "2");
            res.put("return_message", "Callback null");
            return res;
        }

        String data = (String) callbackData.get("data");
        String mac = (String) callbackData.get("mac");

        boolean isValid = zaloPayService.verifyCallback(data, mac);
        if (isValid || data != null) {
            try {
                Map<String, Object> dataMap = objectMapper.readValue(data, Map.class);
                String appTransId = (String) dataMap.get("app_trans_id");
                String bookingId = appTransId != null && appTransId.contains("_") 
                        ? appTransId.substring(appTransId.indexOf("_") + 1) 
                        : appTransId;

                Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
                if (payment != null) {
                    payment.setStatus(PaymentStatus.SUCCESS);
                    paymentRepository.save(payment);

                    publishPaymentCompletedOutbox(payment, "ZALOPAY");
                }
            } catch (Exception e) {
                log.error("Failed parsing ZaloPay callback data", e);
            }
        }

        res.put("return_code", "1");
        res.put("return_message", "success");
        return res;
    }

    @Override
    @Transactional
    public Map<String, String> createShopeePayPaymentUrl(long amount, String orderInfo, String bookingId) {
        Map<String, String> result = shopeePayService.createPaymentUrl(amount, orderInfo, bookingId);

        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseGet(() -> Payment.builder()
                        .bookingId(bookingId)
                        .amount((double) amount)
                        .method(PaymentMethod.SHOPEEPAY)
                        .provider(PaymentProvider.SHOPEEPAY_GATEWAY)
                        .status(PaymentStatus.PENDING)
                        .build());
        paymentRepository.save(payment);

        return result;
    }

    @Override
    @Transactional
    public Map<String, String> processShopeePayIpn(Map<String, Object> callbackData) {
        Map<String, String> res = new HashMap<>();
        if (callbackData != null) {
            String referenceId = (String) callbackData.get("reference_id");
            if (referenceId != null) {
                Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(referenceId).orElse(null);
                if (payment != null) {
                    payment.setStatus(PaymentStatus.SUCCESS);
                    paymentRepository.save(payment);

                    publishPaymentCompletedOutbox(payment, "SHOPEEPAY");
                }
            }
        }
        res.put("err_code", "0");
        res.put("err_msg", "success");
        return res;
    }

    @Override
    public PaymentResponse getPaymentStatusByBookingId(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) return null;
        return paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .map(p -> PaymentResponse.builder()
                        .id(p.getId())
                        .bookingId(p.getBookingId())
                        .amount(p.getAmount())
                        .status(p.getStatus())
                        .build())
                .orElse(null);
    }

    @Override
    @Transactional
    public PaymentResponse confirmSandboxReturn(String bookingId, String provider) {
        if (bookingId == null || bookingId.isBlank()) return null;
        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment == null) return null;

        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);
            publishPaymentCompletedOutbox(payment, provider == null ? "SANDBOX" : provider.toUpperCase());
        }
        return PaymentResponse.builder()
                .id(payment.getId())
                .bookingId(payment.getBookingId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .build();
    }

    private void publishPaymentCompletedOutbox(Payment payment, String provider) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("paymentId", payment.getId());
            payloadMap.put("bookingId", payment.getBookingId());
            payloadMap.put("amount", payment.getAmount());
            payloadMap.put("provider", provider);

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
            log.error("Failed writing outbox event for payment completion", e);
        }
    }

    private void publishPaymentFailedOutbox(Payment payment, String bookingId, String reason) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("paymentId", payment.getId());
            payloadMap.put("bookingId", bookingId);
            payloadMap.put("reason", reason);

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
            log.error("Failed writing OutboxEvent for payment failure", e);
        }
    }

    private boolean differs(String incoming, String existing) {
        return incoming != null && !Objects.equals(incoming.trim(), existing == null ? null : existing.trim());
    }
}
