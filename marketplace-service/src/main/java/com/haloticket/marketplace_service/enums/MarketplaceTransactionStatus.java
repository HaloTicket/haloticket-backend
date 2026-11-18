package com.haloticket.marketplace_service.enums;
/**
 * Trạng thái giao dịch mua vé bán lại: chờ thanh toán, đang xử lý thanh toán, đang chuyển vé,
 * hoàn tất, chờ hoàn tiền, đã hoàn tiền, đã hủy và hết hạn.
 */
public enum MarketplaceTransactionStatus {
    PENDING_PAYMENT,
    PAYMENT_PROCESSING,
    TRANSFERRING,
    COMPLETED,
    REFUND_PENDING,
    REFUNDED,
    CANCELLED,
    EXPIRED
}
