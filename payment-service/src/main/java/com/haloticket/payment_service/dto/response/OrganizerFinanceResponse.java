package com.haloticket.payment_service.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/** Tổng hợp tài chính của ban tổ chức (số tiền dạng chuỗi) và danh sách từng sự kiện. */
@Data @Builder
public class OrganizerFinanceResponse {
    private String grossCollected;
    private String platformFees;
    private String refunds;
    private String heldAmount;
    private String availableAmount;
    private String processingAmount;
    private String paidAmount;
    private List<EventFinanceItem> items;

    /** Số liệu tài chính của một sự kiện: đã thu, phí, hoàn tiền, đang giữ, khả dụng, đang xử lý, đã chi và điều kiện chi trả. */
    @Data @Builder
    public static class EventFinanceItem {
        private String eventId;
        private String title;
        private String imageUrl;
        private String endAt;
        private String grossCollected;
        private String platformFees;
        private String refunds;
        private String heldAmount;
        private String availableAmount;
        private String paidAmount;
        private String processingAmount;
        private String reconciliationStatus;
        private Eligibility eligibility;
        private Map<String, Eligibility> eligibilityByType;
        private String maximumAdvanceAmount;
    }
    /** Điều kiện chi trả: có đủ điều kiện hay không và các mã lý do. */
    @Data @Builder public static class Eligibility { private boolean eligible; private List<String> reasonCodes; }
}
