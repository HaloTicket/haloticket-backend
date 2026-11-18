package com.haloticket.payment_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** Dòng tiền của ban tổ chức trong một khoảng ngày: tổng thu, hoàn tiền, dòng tiền ròng và chuỗi theo ngày. */
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

    /** Dòng tiền của một ngày: tổng thu, hoàn tiền và dòng tiền ròng. */
    @Data
    @Builder
    public static class DailyCashFlow {
        private LocalDate date;
        private String grossAmount;
        private String refundAmount;
        private String netCashFlow;
    }
}
