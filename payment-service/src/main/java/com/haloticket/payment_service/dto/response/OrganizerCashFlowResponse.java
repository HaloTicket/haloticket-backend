package com.haloticket.payment_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class OrganizerCashFlowResponse {
    private String from;
    private String to;
    private List<DailyCashFlow> series;
    private String grossAmount;
    private String refundAmount;
    private String netCashFlow;
    private boolean refundDataAvailable;
    private long undatedCompletedRefundCount;
    private String refundDateNote;

    @Data
    @Builder
    public static class DailyCashFlow {
        private LocalDate date;
        private String grossAmount;
        private String refundAmount;
        private String netCashFlow;
    }
}
