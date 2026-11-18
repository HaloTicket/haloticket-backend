package com.haloticket.marketplace_service.enums;
/**
 * Trạng thái tin bán lại: đang bán, đang được giữ chỗ, đã bán, đã hủy, hết hạn,
 * bị tạm dừng do sự kiện và chờ ban tổ chức duyệt.
 */
public enum ListingStatus { ACTIVE, RESERVED, SOLD, CANCELLED, EXPIRED, PAUSED_BY_EVENT, PENDING_APPROVAL }
