package com.haloticket.payment_service.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

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
    @Data @Builder public static class Eligibility { private boolean eligible; private List<String> reasonCodes; }
}
