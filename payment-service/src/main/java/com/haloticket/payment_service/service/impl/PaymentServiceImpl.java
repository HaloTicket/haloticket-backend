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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cài đặt nghiệp vụ thanh toán: lưu thanh toán, quản lý tài khoản nhận tiền của organizer và xử lý IPN/webhook
 * của VNPay, SePay, MoMo, ZaloPay, ShopeePay. Khi xác nhận hoặc từ chối thanh toán, dịch vụ ghi sự kiện outbox
 * ({@code PAYMENT_COMPLETED}, {@code PAYMENT_FAILED}) trong cùng transaction để các dịch vụ khác nhận qua Kafka.
 */
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

    /** Khởi tạo dịch vụ với các repository, các dịch vụ cổng thanh toán và {@code ObjectMapper} để tạo payload outbox. */
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

    /**
     * Tạo bản ghi thanh toán mới ở trạng thái {@code PENDING}.
     *
     * @param request thông tin booking, số tiền, phương thức và nhà cung cấp
     * @return thanh toán vừa lưu; {@code null} nếu {@code request} rỗng
     */
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

    /**
     * Tạo hoặc cập nhật tài khoản nhận tiền của organizer, khóa bản ghi khi cập nhật để tránh ghi đồng thời.
     * Chỉ cập nhật trường nào được gửi lên. Nếu thông tin ngân hàng hoặc phiên bản định danh thay đổi thì tài khoản
     * bị đặt lại về chưa xác minh và tăng {@code accountVersion}.
     *
     * @param request thông tin tài khoản, bắt buộc có {@code organizerUserId}
     * @return tài khoản sau khi lưu; {@code null} nếu thiếu yêu cầu hoặc mã organizer
     */
    @Override
    @Transactional
    public OrganizerPayoutAccountResponse saveOrUpdatePayoutAccount(OrganizerPayoutAccountRequest request) {
        if (request == null || request.getOrganizerUserId() == null) return null;

        OrganizerPayoutAccount account = organizerPayoutAccountRepository
                .findForUpdate(request.getOrganizerUserId())
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

    /**
     * Lấy tài khoản nhận tiền của organizer.
     *
     * @param userId mã người dùng organizer
     * @return tài khoản; {@code null} nếu mã rỗng hoặc chưa có tài khoản
     */
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

    /**
     * Xử lý IPN của VNPay. Sai checksum trả mã 97. Thanh toán đã thành công thì trả 00 (idempotent). Mã phản hồi
     * {@code 00} chuyển thanh toán sang {@code SUCCESS} và ghi outbox {@code PAYMENT_COMPLETED}; mã khác chuyển
     * sang {@code FAILED} và ghi outbox {@code PAYMENT_FAILED}. Số tiền VNPay gửi được chia 100.
     *
     * @param allParams tham số IPN VNPay gửi về
     * @return map {@code RspCode} và {@code Message} trả lại cho VNPay
     */
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

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            response.put("RspCode", "00");
            response.put("Message", "Already Confirmed");
            return response;
        }
        if (payment.getStatus() == PaymentStatus.FAILED && !"00".equals(vnp_ResponseCode)) {
            response.put("RspCode", "01");
            response.put("Message", "Transaction Failed");
            return response;
        }

        if ("00".equals(vnp_ResponseCode)) {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);

            // ghi sự kiện outbox PAYMENT_COMPLETED
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

    /**
     * Sinh thông tin VietQR qua SePay và tạo bản ghi thanh toán {@code PENDING} cho booking nếu chưa có,
     * bổ sung mã đặt chỗ vào bản ghi cũ khi còn thiếu.
     *
     * @param amount số tiền cần chuyển (VND)
     * @param bookingCode mã đặt chỗ
     * @param bookingId mã booking
     * @return thông tin QR và chuyển khoản
     */
    @Override
    @Transactional
    public Map<String, Object> createVietQrPayload(long amount, String bookingCode, String bookingId) {
        Map<String, Object> payload = sePayService.createVietQrPayload(amount, bookingCode, bookingId);

        // ghi nhận thanh toán đang chờ trong DB
        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseGet(() -> Payment.builder()
                        .bookingId(bookingId)
                        .bookingCode(bookingCode)
                        .amount((double) amount)
                        .method(PaymentMethod.VIETQR)
                        .provider(PaymentProvider.SEPAY)
                        .status(PaymentStatus.PENDING)
                        .build());
                        
        if (bookingCode != null && !bookingCode.isBlank() && payment.getBookingCode() == null) {
            payment.setBookingCode(bookingCode);
        }
        paymentRepository.save(payment);

        return payload;
    }

    /**
     * Xử lý webhook SePay. Tìm thanh toán theo trường {@code code} (bỏ tiền tố HALO), nếu không thấy thì tìm theo
     * mẫu {@code HALO-<số>} trong nội dung chuyển khoản. Chỉ xác nhận khi thanh toán còn {@code PENDING} và số tiền
     * chuyển đúng bằng số tiền cần trả, rồi ghi outbox {@code PAYMENT_COMPLETED}.
     *
     * @param webhookData dữ liệu webhook SePay gửi về
     * @return map {@code status}: {@code success}, {@code invalid_amount}, {@code received} hoặc {@code error}
     */
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

        // 1. thử tìm theo trường 'code' nếu SePay VA có gửi
        if (code != null && !code.isBlank()) {
            String cleanCode = code.toUpperCase().startsWith("HALO") ? code.substring(4).trim() : code;
            payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(cleanCode)
                    .orElseGet(() -> paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(code).orElse(null));
        }

        // 2. thử tìm theo đoạn nội dung nếu có tiền tố HALO
        if (payment == null && content != null && !content.isBlank()) {
            Matcher matcher = Pattern.compile("HALO[- ]?(\\d+)").matcher(content.toUpperCase());
            if (matcher.find()) {
                String bookingCodeRef = "HALO-" + matcher.group(1);
                payment = paymentRepository.findFirstByBookingCodeOrderByCreatedAtDesc(bookingCodeRef)
                        .orElseGet(() -> paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingCodeRef).orElse(null));
            }
        }

        if (payment != null && payment.getStatus() == PaymentStatus.PENDING) {
            Object transferred = webhookData.get("transferAmount");
            double transferredAmount;
            try {
                transferredAmount = Double.parseDouble(String.valueOf(transferred));
            } catch (NumberFormatException e) {
                res.put("status", "invalid_amount");
                return res;
            }
            if (payment.getAmount() == null || Double.compare(transferredAmount, payment.getAmount()) != 0) {
                res.put("status", "invalid_amount");
                return res;
            }
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

    /**
     * Tạo liên kết thanh toán MoMo rồi ghi nhận thanh toán {@code PENDING} cho booking nếu chưa có.
     *
     * @param amount số tiền (VND)
     * @param orderInfo nội dung đơn hàng
     * @param bookingId mã booking
     * @return kết quả từ MoMo, gồm {@code paymentUrl}
     */
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

    /**
     * Xử lý IPN của MoMo. Chỉ xác nhận thanh toán khi đang {@code PENDING}, chữ ký hợp lệ, {@code resultCode} bằng 0
     * và số tiền khớp; khi đó chuyển {@code SUCCESS} và ghi outbox {@code PAYMENT_COMPLETED}.
     *
     * @param allParams tham số IPN MoMo gửi về
     * @return map {@code status} là {@code success} hoặc {@code failed}
     */
    @Override
    @Transactional
    public Map<String, String> processMoMoIpn(Map<String, String> allParams) {
        Map<String, String> res = new HashMap<>();
        boolean isValid = moMoService.verifySignature(allParams);

        String bookingId = allParams.get("orderId");
        String resultCode = allParams.get("resultCode");

        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (payment != null && payment.getStatus() == PaymentStatus.PENDING
                && "0".equals(resultCode) && isValid
                && String.valueOf(payment.getAmount().longValue()).equals(allParams.get("amount"))) {
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

    /**
     * Tạo liên kết thanh toán ZaloPay rồi ghi nhận thanh toán {@code PENDING} cho booking nếu chưa có.
     *
     * @param amount số tiền (VND)
     * @param orderInfo nội dung đơn hàng
     * @param bookingId mã booking
     * @return kết quả từ ZaloPay, gồm {@code paymentUrl}
     */
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

    /**
     * Xử lý callback ZaloPay. Nếu mac hợp lệ, lấy mã booking từ {@code app_trans_id} (phần sau dấu gạch dưới) và xác nhận
     * thanh toán đang {@code PENDING} có số tiền khớp. Luôn trả {@code return_code = 1} để ZaloPay không gửi lại.
     *
     * @param callbackData dữ liệu callback gồm {@code data} và {@code mac}
     * @return map {@code return_code} và {@code return_message}; {@code return_code = 2} khi callback rỗng
     */
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
        if (isValid) {
            try {
                Map<String, Object> dataMap = objectMapper.readValue(data, Map.class);
                String appTransId = (String) dataMap.get("app_trans_id");
                String bookingId = appTransId != null && appTransId.contains("_") 
                        ? appTransId.substring(appTransId.indexOf("_") + 1) 
                        : appTransId;

                Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
                if (payment != null && payment.getStatus() == PaymentStatus.PENDING
                        && String.valueOf(payment.getAmount().longValue()).equals(String.valueOf(dataMap.get("amount")))) {
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

    /**
     * Tạo liên kết thanh toán ShopeePay rồi ghi nhận thanh toán {@code PENDING} cho booking nếu chưa có.
     *
     * @param amount số tiền (VND)
     * @param orderInfo nội dung đơn hàng
     * @param bookingId mã booking
     * @return kết quả từ ShopeePay
     */
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

    /**
     * Callback ShopeePay chưa được hỗ trợ: luôn trả {@code err_code = 1} kèm thông báo chưa cấu hình xác minh.
     *
     * @param callbackData dữ liệu callback (không dùng)
     * @return map {@code err_code} và {@code err_msg}
     */
    @Override
    @Transactional
    public Map<String, String> processShopeePayIpn(Map<String, Object> callbackData) {
        return Map.of("err_code", "1", "err_msg", "ShopeePay callback verification is not configured");
    }

    /**
     * Lấy trạng thái thanh toán mới nhất của booking. Với VietQR còn {@code PENDING}, hàm chủ động hỏi SePay;
     * nếu đã nhận tiền thì chuyển {@code SUCCESS} và ghi outbox {@code PAYMENT_COMPLETED}.
     *
     * @param bookingId mã booking
     * @return thông tin thanh toán; {@code null} nếu mã rỗng hoặc chưa có thanh toán
     */
    @Override
    public PaymentResponse getPaymentStatusByBookingId(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) return null;
        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (payment == null) return null;

        if (payment.getMethod() == PaymentMethod.VIETQR && payment.getStatus() == PaymentStatus.PENDING) {
            boolean isPaid = sePayService.checkPaymentActively(bookingId, payment.getBookingCode(), payment.getAmount());
            if (isPaid) {
                payment.setStatus(PaymentStatus.SUCCESS);
                paymentRepository.save(payment);
                publishPaymentCompletedOutbox(payment, "SEPAY");
            }
        }

        return PaymentResponse.builder()
                .id(payment.getId())
                .bookingId(payment.getBookingId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .build();
    }

    /**
     * Lấy phương thức thanh toán của từng booking.
     *
     * @param bookingIds danh sách mã booking
     * @return map {@code bookingId -> tên phương thức}; map rỗng nếu danh sách rỗng
     */
    @Override
    public Map<String, String> getPaymentMethodsByBookingIds(List<String> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty()) return new HashMap<>();
        List<Payment> payments = paymentRepository.findAllByBookingIdIn(bookingIds);
        Map<String, String> result = new HashMap<>();
        for (Payment payment : payments) {
            result.put(payment.getBookingId(), payment.getMethod() != null ? payment.getMethod().name() : null);
        }
        return result;
    }

    /**
     * Xác nhận thanh toán thành công khi người dùng quay về từ cổng ở môi trường sandbox. Nếu chưa {@code SUCCESS}
     * thì chuyển {@code SUCCESS} và ghi outbox {@code PAYMENT_COMPLETED}.
     *
     * @param bookingId mã booking
     * @param provider tên cổng; {@code null} thì ghi là {@code SANDBOX}
     * @return thông tin thanh toán; {@code null} nếu mã rỗng hoặc chưa có thanh toán
     */
    @Override
    @Transactional
    public PaymentResponse confirmSandboxReturn(String bookingId, String provider) {
        if (bookingId == null || bookingId.isBlank()) return null;
        Payment payment = paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
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

    /**
     * Ghi sự kiện outbox {@code PAYMENT_COMPLETED} (topic {@code haloticket.payment.completed.v1}) ở trạng thái
     * {@code PENDING} để {@code OutboxPublisher} gửi đi. Lỗi khi ghi chỉ được log, không làm hỏng giao dịch gọi.
     *
     * @param payment thanh toán vừa thành công
     * @param provider tên cổng thanh toán ghi vào payload
     */
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

    /**
     * Ghi sự kiện outbox {@code PAYMENT_FAILED} (topic {@code haloticket.payment.failed.v1}) kèm lý do thất bại.
     * Lỗi khi ghi chỉ được log.
     *
     * @param payment thanh toán thất bại
     * @param bookingId mã booking
     * @param reason lý do thất bại
     */
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

    /**
     * Cho biết giá trị gửi lên có khác giá trị đang lưu hay không, so sánh sau khi cắt khoảng trắng.
     * Giá trị gửi lên là {@code null} nghĩa là không thay đổi.
     *
     * @param incoming giá trị mới
     * @param existing giá trị hiện có
     * @return {@code true} nếu có thay đổi
     */
    private boolean differs(String incoming, String existing) {
        return incoming != null && !Objects.equals(incoming.trim(), existing == null ? null : existing.trim());
    }
}
