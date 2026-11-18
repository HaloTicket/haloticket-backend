package com.haloticket.payment_service.service;

import com.haloticket.payment_service.dto.request.PayoutCreateRequest;
import com.haloticket.payment_service.dto.response.PayoutResponse;

import java.util.List;
import com.haloticket.payment_service.dto.response.EventFinanceSource;
import com.haloticket.payment_service.dto.response.OrganizerFinanceResponse;
import com.haloticket.payment_service.dto.response.PayoutQuoteResponse;
import com.haloticket.payment_service.dto.response.OrganizerCashFlowResponse;
import java.time.LocalDate;

/** Nghiệp vụ rút tiền của organizer: báo giá, tạo yêu cầu, duyệt hoặc từ chối của admin, giải ngân và các số liệu tài chính theo sự kiện. */
public interface PayoutService {
    /** Tạo yêu cầu rút tiền dựa trên báo giá còn hiệu lực; cùng {@code idempotencyKey} thì trả lại yêu cầu cũ. */
    PayoutResponse requestPayout(String organizerId, String authorization, PayoutCreateRequest request, String idempotencyKey);
    /** Admin duyệt yêu cầu rút tiền đang chờ ({@code PENDING}). */
    PayoutResponse approvePayout(String payoutId);
    /** Admin chuyển yêu cầu đang chờ sang trạng thái tranh chấp kèm lý do. */
    PayoutResponse disputePayout(String payoutId, String reason);
    /** Mở lại yêu cầu đang tranh chấp, đưa về {@code PENDING}. */
    PayoutResponse reopenPayout(String payoutId);
    /** Admin từ chối yêu cầu đang chờ kèm lý do. */
    PayoutResponse rejectPayout(String payoutId, String reason);
    /** Liệt kê các yêu cầu rút tiền đang chờ duyệt. */
    List<PayoutResponse> getPendingPayouts();
    /** Liệt kê mọi yêu cầu rút tiền cho trang quản trị, kèm tên sự kiện nếu tra được. */
    List<PayoutResponse> getAllAdminPayouts();
    /** Liệt kê các yêu cầu rút tiền của một organizer. */
    List<PayoutResponse> getOrganizerPayouts(String organizerId);
    /** Tổng hợp tài chính của organizer theo từng sự kiện: doanh thu, phí nền tảng, hoàn tiền, số tiền được rút và điều kiện rút. */
    OrganizerFinanceResponse getOrganizerFinance(String organizerId, String authorization);
    /** Thống kê dòng tiền theo ngày của organizer trong khoảng ngày chọn, cho mọi sự kiện của họ. */
    OrganizerCashFlowResponse getOrganizerAnalytics(String organizerId, String authorization, LocalDate from, LocalDate to);
    /** Thống kê dòng tiền theo ngày của organizer, giới hạn trong một sự kiện nếu có {@code eventId}. */
    OrganizerCashFlowResponse getOrganizerAnalytics(String organizerId, String authorization, LocalDate from, LocalDate to, String eventId);
    /** Tạo báo giá rút tiền cho một sự kiện; báo giá chỉ được lưu khi organizer đủ điều kiện. */
    PayoutQuoteResponse quotePayout(String organizerId, String authorization, String eventId, String type, Long requestedAmount);
    /** Lấy một yêu cầu rút tiền của organizer; không thuộc organizer thì coi như không tồn tại. */
    PayoutResponse getOrganizerPayout(String organizerId, String payoutId);
    /** Tìm yêu cầu rút tiền của organizer theo khóa idempotency. */
    PayoutResponse getByIdempotencyKey(String organizerId, String key);
    /** Organizer hủy yêu cầu rút tiền còn ở trạng thái {@code PENDING}. */
    PayoutResponse cancelPayout(String organizerId, String payoutId);
    /** Bắt đầu giải ngân: chuyển yêu cầu đã duyệt sang {@code PROCESSING}. */
    PayoutResponse startDisbursement(String payoutId);
    /** Hoàn tất giải ngân với mã tham chiếu chuyển khoản, thời điểm chuyển và biên lai. */
    PayoutResponse completeDisbursement(String payoutId, String reference, java.time.LocalDateTime transferredAt, String receiptUrl);
    /** Lấy mã người dùng organizer từ token xác thực, thông qua user-service. */
    String resolveOrganizerId(String authorization);
    /** Lấy số liệu tài chính nguồn của một sự kiện (thu, hoàn, đang hoàn) từ booking-service và các thanh toán đã xác nhận. */
    FinanceSource getFinanceSource(String eventId, String authorization);
    /**
     * Số liệu tài chính nguồn của một sự kiện, tính bằng VND.
     *
     * @param grossCollected tổng tiền đã thu từ các thanh toán thành công
     * @param refunds tổng tiền hoàn đã hoàn tất
     * @param paidBookingAmount tổng giá trị booking đã thanh toán theo booking-service, dùng để đối chiếu
     * @param pendingRefunds tổng tiền hoàn đang chờ hoặc đang xử lý
     */
    record FinanceSource(long grossCollected, long refunds, long paidBookingAmount, long pendingRefunds) {}
}
