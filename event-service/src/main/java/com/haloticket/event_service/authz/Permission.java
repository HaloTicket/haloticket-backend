package com.haloticket.event_service.authz;

/** Các quyền chi tiết trong một sự kiện (sửa sự kiện, chính sách, voucher, thành viên, tài chính, chợ vé, đơn hàng, thống kê, check-in). */
public enum Permission {
    EVENT_EDIT,
    POLICY_WRITE,
    POLICY_READ,
    VOUCHER_MANAGE,
    MEMBERS_VIEW,
    MEMBERS_MANAGE,
    OWNER_TRANSFER,
    FINANCE,
    MARKETPLACE_MODERATE,
    MARKETPLACE_VIEW,
    ORDERS_VIEW,
    ANALYTICS_VIEW,
    CHECKIN_SCAN,
    CHECKIN_VIEW
}
