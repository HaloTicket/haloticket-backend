package com.haloticket.booking_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Thông tin voucher: mã, loại và mức giảm, giới hạn áp dụng, thời gian hiệu lực và trạng thái. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherResponse {
    private String id;
    private String eventId;
    private String title;
    private String code;
    private String terms;
    private String discountType;
    private Double discountValue;
    private String applyTicketLimitType;
    private Integer ticketLimitCount;
    private Integer maxOrdersPerBuyer;
    private Integer minTicketsPerOrder;
    private Integer maxTicketsPerOrder;
    private String applyScope;
    private List<String> applicableShowtimes;
    private Integer usedCount;
    private String startDate;
    private String endDate;
    private String status;
    private Boolean isActive;
    private String createdAt;
    private String updatedAt;
}
