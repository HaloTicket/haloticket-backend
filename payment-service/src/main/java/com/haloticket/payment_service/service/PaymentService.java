package com.haloticket.payment_service.service;

import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;

import java.util.Map;
import java.util.List;

/** Nghiệp vụ thanh toán: tạo thanh toán, tài khoản nhận tiền của organizer và xử lý các cổng VNPay, SePay, MoMo, ZaloPay, ShopeePay. */
public interface PaymentService {
    /** Tạo bản ghi thanh toán trạng thái {@code PENDING} từ yêu cầu; trả về {@code null} nếu yêu cầu rỗng. */
    PaymentResponse createPayment(PaymentCreateRequest request);
    /** Tạo hoặc cập nhật tài khoản nhận tiền của organizer; đổi thông tin ngân hàng thì tài khoản bị đặt lại về chưa xác minh. */
    OrganizerPayoutAccountResponse saveOrUpdatePayoutAccount(OrganizerPayoutAccountRequest request);
    /** Lấy tài khoản nhận tiền của organizer theo mã người dùng; {@code null} nếu chưa có. */
    OrganizerPayoutAccountResponse getPayoutAccountByUserId(String userId);

    /** Xử lý IPN của VNPay: kiểm tra checksum, cập nhật trạng thái thanh toán và trả về mã phản hồi {@code RspCode} cho VNPay. */
    Map<String, String> processVnPayIpn(Map<String, String> allParams);
    /** Tạo thông tin chuyển khoản VietQR (SePay) và ghi nhận thanh toán đang chờ. */
    Map<String, Object> createVietQrPayload(long amount, String bookingCode, String bookingId);
    /** Xử lý webhook SePay: đối chiếu thanh toán đang chờ theo mã hoặc nội dung chuyển khoản và xác nhận khi đúng số tiền. */
    Map<String, String> processSepayWebhook(Map<String, Object> webhookData);

    /** Tạo liên kết thanh toán MoMo và ghi nhận thanh toán đang chờ. */
    Map<String, String> createMoMoPaymentUrl(long amount, String orderInfo, String bookingId);
    /** Xử lý IPN của MoMo: xác nhận thanh toán khi chữ ký, mã kết quả và số tiền đều hợp lệ. */
    Map<String, String> processMoMoIpn(Map<String, String> allParams);

    /** Tạo liên kết thanh toán ZaloPay và ghi nhận thanh toán đang chờ. */
    Map<String, String> createZaloPayPaymentUrl(long amount, String orderInfo, String bookingId);
    /** Xử lý callback của ZaloPay: kiểm tra mac rồi xác nhận thanh toán nếu số tiền khớp. */
    Map<String, String> processZaloPayIpn(Map<String, Object> callbackData);

    /** Tạo liên kết thanh toán ShopeePay và ghi nhận thanh toán đang chờ. */
    Map<String, String> createShopeePayPaymentUrl(long amount, String orderInfo, String bookingId);
    /** Xử lý callback của ShopeePay. */
    Map<String, String> processShopeePayIpn(Map<String, Object> callbackData);

    /** Lấy trạng thái thanh toán mới nhất của booking; với VietQR đang chờ thì chủ động hỏi SePay để cập nhật. */
    PaymentResponse getPaymentStatusByBookingId(String bookingId);
    /** Xác nhận thanh toán thành công khi người dùng quay về từ cổng ở môi trường sandbox. */
    PaymentResponse confirmSandboxReturn(String bookingId, String provider);
    /** Lấy phương thức thanh toán của từng booking trong danh sách, trả về map {@code bookingId -> tên phương thức}. */
    Map<String, String> getPaymentMethodsByBookingIds(List<String> bookingIds);
}
