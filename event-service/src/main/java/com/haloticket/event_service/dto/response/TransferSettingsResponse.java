package com.haloticket.event_service.dto.response;

/** Cài đặt chuyển nhượng vé của sự kiện: chính sách bán lại, chính sách tặng vé (mỗi cái có thể {@code null} nếu chưa thiết lập) và cờ cho biết người gọi có quyền sửa. */
public record TransferSettingsResponse(ResalePolicyResponse resale, GiftPolicyResponse gift, boolean canWrite) {}
