package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.client.UserServiceClient;
import com.haloticket.payment_service.config.SePayConfig;
import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;
import com.haloticket.payment_service.service.PaymentService;
import com.haloticket.payment_service.service.VNPayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API thanh toán {@code /api/payments}: tạo thanh toán, tạo URL hoặc payload cho các cổng (VNPay, VietQR/SePay, MoMo, ZaloPay, ShopeePay),
 * nhận IPN/webhook, tra cứu trạng thái và quản lý tài khoản nhận tiền của ban tổ chức.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final UserServiceClient userServiceClient;
    private final VNPayService vnPayService;
    private final SePayConfig sePayConfig;

    @Value("${payment.sandbox-return-confirmation-enabled:false}")
    private boolean sandboxReturnConfirmationEnabled;

    /**
     * Tạo bản ghi thanh toán mới.
     *
     * @param request mã đơn, số tiền, phương thức và nhà cung cấp
     * @return thanh toán vừa tạo, HTTP 201
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@RequestBody PaymentCreateRequest request) {
        return new ResponseEntity<>(paymentService.createPayment(request), HttpStatus.CREATED);
    }

    // 1. VNPAY
    /**
     * Tạo URL thanh toán VNPay cho một đơn.
     *
     * @param amount số tiền (VND)
     * @param orderInfo nội dung thanh toán, tùy chọn
     * @param bookingId mã đơn đặt chỗ, tùy chọn
     * @param request yêu cầu HTTP (lấy IP người trả tiền)
     * @return map chứa khóa {@code paymentUrl}
     */
    @PostMapping("/vnpay/create-url")
    public ResponseEntity<Map<String, String>> createVnPayUrl(
            @RequestParam("amount") long amount,
            @RequestParam(value = "orderInfo", required = false) String orderInfo,
            @RequestParam(value = "bookingId", required = false) String bookingId,
            HttpServletRequest request) {
        String paymentUrl = vnPayService.createPaymentUrl(request, amount, orderInfo, bookingId);
        Map<String, String> response = new HashMap<>();
        response.put("paymentUrl", paymentUrl);
        return ResponseEntity.ok(response);
    }

    /**
     * Nhận IPN từ VNPay (GET), xác thực chữ ký và cập nhật thanh toán.
     *
     * @param allParams toàn bộ tham số VNPay gửi về
     * @return phản hồi {@code RspCode} và {@code Message} theo quy ước của VNPay
     */
    @GetMapping("/vnpay/ipn")
    public ResponseEntity<Map<String, String>> vnpayIpn(@RequestParam Map<String, String> allParams) {
        Map<String, String> result = paymentService.processVnPayIpn(allParams);
        return ResponseEntity.ok(result);
    }

    // 2. SePay (VietQR 24/7)
    /**
     * Tạo dữ liệu mã QR VietQR (chuyển khoản qua SePay) cho một đơn.
     *
     * @param amount số tiền (VND)
     * @param bookingCode mã đơn dạng đọc được, tùy chọn
     * @param bookingId mã đơn đặt chỗ
     * @return payload gồm thông tin tài khoản và nội dung chuyển khoản
     */
    @PostMapping("/vietqr/create-payload")
    public ResponseEntity<Map<String, Object>> createVietQrPayload(
            @RequestParam("amount") long amount,
            @RequestParam(value = "bookingCode", required = false) String bookingCode,
            @RequestParam("bookingId") String bookingId) {
        return ResponseEntity.ok(paymentService.createVietQrPayload(amount, bookingCode, bookingId));
    }

    /**
     * Nhận webhook SePay khi có tiền vào. Header Authorization phải là {@code Apikey <khóa>} (so sánh thời gian hằng định).
     *
     * @param authorization header Authorization
     * @param webhookData dữ liệu giao dịch từ SePay
     * @return kết quả xử lý
     * @throws ResponseStatusException HTTP 401 khi thiếu hoặc sai khóa
     */
    @PostMapping("/sepay/webhook")
    public ResponseEntity<Map<String, String>> sepayWebhook(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> webhookData) {
        String configuredKey = sePayConfig.getApiKey();
        // chưa cấu hình khóa thì từ chối hẳn: nếu không, header "Apikey " (rỗng) sẽ khớp và ai cũng giả được webhook
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String expected = "Apikey " + configuredKey;
        if (authorization == null || !MessageDigest.isEqual(
                authorization.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(paymentService.processSepayWebhook(webhookData));
    }

    // 3. MoMo
    /**
     * Tạo URL thanh toán MoMo.
     *
     * @param amount số tiền (VND)
     * @param orderInfo nội dung thanh toán, tùy chọn
     * @param bookingId mã đơn đặt chỗ
     * @return map chứa URL thanh toán
     */
    @PostMapping("/momo/create-url")
    public ResponseEntity<Map<String, String>> createMoMoUrl(
            @RequestParam("amount") long amount,
            @RequestParam(value = "orderInfo", required = false) String orderInfo,
            @RequestParam("bookingId") String bookingId) {
        return ResponseEntity.ok(paymentService.createMoMoPaymentUrl(amount, orderInfo, bookingId));
    }

    /**
     * Nhận IPN từ MoMo (JSON), chuyển giá trị về chuỗi rồi xử lý.
     *
     * @param callbackData dữ liệu callback của MoMo
     * @return kết quả xử lý
     */
    @PostMapping("/momo/ipn")
    public ResponseEntity<Map<String, String>> momoIpn(@RequestBody Map<String, Object> callbackData) {
        Map<String, String> params = new HashMap<>();
        callbackData.forEach((key, value) -> params.put(key, value == null ? "" : String.valueOf(value)));
        return ResponseEntity.ok(paymentService.processMoMoIpn(params));
    }

    // 4. ZaloPay
    /**
     * Tạo URL thanh toán ZaloPay.
     *
     * @param amount số tiền (VND)
     * @param orderInfo nội dung thanh toán, tùy chọn
     * @param bookingId mã đơn đặt chỗ
     * @return map chứa URL thanh toán
     */
    @PostMapping("/zalopay/create-url")
    public ResponseEntity<Map<String, String>> createZaloPayUrl(
            @RequestParam("amount") long amount,
            @RequestParam(value = "orderInfo", required = false) String orderInfo,
            @RequestParam("bookingId") String bookingId) {
        return ResponseEntity.ok(paymentService.createZaloPayPaymentUrl(amount, orderInfo, bookingId));
    }

    /**
     * Nhận callback từ ZaloPay và cập nhật thanh toán.
     *
     * @param callbackData dữ liệu callback của ZaloPay
     * @return kết quả xử lý
     */
    @PostMapping("/zalopay/ipn")
    public ResponseEntity<Map<String, String>> zalopayIpn(@RequestBody Map<String, Object> callbackData) {
        return ResponseEntity.ok(paymentService.processZaloPayIpn(callbackData));
    }

    // 5. ShopeePay
    /**
     * Tạo URL thanh toán ShopeePay.
     *
     * @param amount số tiền (VND)
     * @param orderInfo nội dung thanh toán, tùy chọn
     * @param bookingId mã đơn đặt chỗ
     * @return map chứa URL thanh toán
     */
    @PostMapping("/shopeepay/create-url")
    public ResponseEntity<Map<String, String>> createShopeePayUrl(
            @RequestParam("amount") long amount,
            @RequestParam(value = "orderInfo", required = false) String orderInfo,
            @RequestParam("bookingId") String bookingId) {
        return ResponseEntity.ok(paymentService.createShopeePayPaymentUrl(amount, orderInfo, bookingId));
    }

    /**
     * Nhận IPN từ ShopeePay và cập nhật thanh toán.
     *
     * @param callbackData dữ liệu callback của ShopeePay
     * @return kết quả xử lý
     */
    @PostMapping("/shopeepay/ipn")
    public ResponseEntity<Map<String, String>> shopeepayIpn(@RequestBody Map<String, Object> callbackData) {
        return ResponseEntity.ok(paymentService.processShopeePayIpn(callbackData));
    }

    // Xác minh thanh toán cho frontend polling hoặc callback
    /**
     * Trả trạng thái thanh toán của một đơn để frontend polling hoặc xử lý callback. Chỉ người mua đơn hoặc admin được xem
     * (kiểm tra bởi interceptor).
     *
     * @param bookingId mã đơn đặt chỗ
     * @return thanh toán, hoặc HTTP 404 nếu chưa có
     */
    @GetMapping("/verify/{bookingId}")
    public ResponseEntity<PaymentResponse> getPaymentStatusByBookingId(@PathVariable("bookingId") String bookingId) {
        PaymentResponse response = paymentService.getPaymentStatusByBookingId(bookingId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Xác nhận thanh toán từ trang trả về của cổng ở môi trường sandbox. Chỉ hoạt động khi bật
     * {@code payment.sandbox-return-confirmation-enabled}, nếu không trả 404.
     *
     * @param bookingId mã đơn đặt chỗ
     * @param provider tên nhà cung cấp thanh toán
     * @return thanh toán sau xác nhận, hoặc HTTP 404 khi không tìm thấy
     */
    @PostMapping("/sandbox/confirm-return")
    public ResponseEntity<PaymentResponse> confirmSandboxReturn(
            @RequestParam("bookingId") String bookingId,
            @RequestParam("provider") String provider) {
        if (!sandboxReturnConfirmationEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        PaymentResponse response = paymentService.confirmSandboxReturn(bookingId, provider);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    /**
     * Tra cứu hàng loạt phương thức thanh toán của nhiều đơn; dành cho booking-service (token nội bộ) và admin.
     *
     * @param bookingIds danh sách mã đơn
     * @return map từ mã đơn sang phương thức thanh toán
     */
    @PostMapping("/methods/bulk")
    public ResponseEntity<Map<String, String>> getPaymentMethodsByBookingIds(@RequestBody List<String> bookingIds) {
        return ResponseEntity.ok(paymentService.getPaymentMethodsByBookingIds(bookingIds));
    }

    /**
     * Tạo hoặc cập nhật tài khoản nhận tiền của ban tổ chức. Mã người dùng và phiên bản định danh lấy từ eKYC, không tin dữ liệu client gửi.
     *
     * @param authorization header Authorization
     * @param request thông tin tài khoản ngân hàng và thuế
     * @return tài khoản sau khi lưu
     * @throws ResponseStatusException HTTP 403 khi chưa đạt eKYC, 503 khi không kiểm tra được eKYC
     */
    @PostMapping("/organizer-payout-account")
    public ResponseEntity<OrganizerPayoutAccountResponse> saveOrUpdatePayoutAccount(
            @RequestHeader("Authorization") String authorization,
            @RequestBody OrganizerPayoutAccountRequest request
    ) {
        var eligibility = requireEligibleOrganizer(authorization);
        request.setOrganizerUserId(eligibility.userId());
        request.setIdentityVersion(eligibility.identityVersion());
        OrganizerPayoutAccountResponse response = paymentService.saveOrUpdatePayoutAccount(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Lấy tài khoản nhận tiền của một ban tổ chức; chỉ chính chủ được xem.
     *
     * @param authorization header Authorization
     * @param userId mã người dùng cần xem
     * @return thông tin tài khoản
     * @throws ResponseStatusException HTTP 403 khi không phải chủ tài khoản
     */
    @GetMapping("/organizer-payout-account/{userId}")
    public ResponseEntity<OrganizerPayoutAccountResponse> getPayoutAccountByUserId(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("userId") String userId
    ) {
        var eligibility = requireEligibleOrganizer(authorization);
        if (!userId.equals(eligibility.userId())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Payout account can only be viewed by its owner");
        }
        OrganizerPayoutAccountResponse response = paymentService.getPayoutAccountByUserId(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Gọi user-service để xác nhận người gọi đã đạt eKYC làm ban tổ chức.
     *
     * @param authorization header Authorization
     * @return thông tin đủ điều kiện, có mã người dùng
     * @throws ResponseStatusException HTTP 403 {@code EKYC_REQUIRED} khi chưa đủ điều kiện, 503 {@code EKYC_STATUS_UNAVAILABLE} khi user-service lỗi
     */
    private OrganizerEligibilityResponse requireEligibleOrganizer(
            String authorization
    ) {
        try {
            var eligibility = userServiceClient.getOrganizerEligibility(authorization);
            if (eligibility == null || !eligibility.eligible() || eligibility.userId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EKYC_REQUIRED");
            }
            return eligibility;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_STATUS_UNAVAILABLE");
        }
    }
}
