package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.VoucherCreateRequest;
import com.haloticket.event_service.dto.request.VoucherUpdateRequest;
import com.haloticket.event_service.dto.response.VoucherResponse;

import java.util.List;
import java.util.Optional;

/** Nghiệp vụ quản lý voucher giảm giá của sự kiện: tạo, sửa, bật tắt, ghi nhận lượt dùng và tra cứu theo mã. */
public interface VoucherService {
    /** Lấy các voucher của sự kiện, mới tạo nhất trước. */
    List<VoucherResponse> getVouchersByEventId(String eventId);
    /** Tạo một voucher (chế độ đơn lẻ) hoặc nhiều voucher có mã ngẫu nhiên (chế độ {@code BULK}) cho sự kiện. */
    List<VoucherResponse> createVoucher(String eventId, VoucherCreateRequest request);
    /** Cập nhật các trường được gửi lên của voucher, trường null giữ nguyên. */
    VoucherResponse updateVoucher(String voucherId, VoucherUpdateRequest request);
    /** Đảo trạng thái voucher giữa {@code ACTIVE} và {@code INACTIVE}. */
    VoucherResponse toggleVoucherStatus(String voucherId);
    /** Ghi nhận voucher đã được dùng cho {@code count} vé, tăng số lượt đã dùng và tự tắt voucher khi chạm giới hạn. */
    VoucherResponse useVoucher(String voucherId, int count);
    /** Tìm voucher đã phát hành (không phải nháp) của sự kiện theo mã, không phân biệt hoa thường, để người mua nhập mã có thể xem điều kiện áp dụng. */
    Optional<VoucherResponse> findReleasedByCode(String eventId, String code);
    /** Xóa voucher theo id. */
    void deleteVoucher(String voucherId);

    /** Lấy id sự kiện mà voucher thuộc về; ném 404 khi voucher không tồn tại. */
    String eventIdOf(String voucherId);
}
