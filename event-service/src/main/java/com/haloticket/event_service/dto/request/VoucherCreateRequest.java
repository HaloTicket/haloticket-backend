package com.haloticket.event_service.dto.request;

import com.haloticket.event_service.enums.ApplyScope;
import com.haloticket.event_service.enums.DiscountType;
import com.haloticket.event_service.enums.VoucherStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherCreateRequest {
    private String creationMode; // "SINGLE" or "BULK"
    private String title;
    private String code; // Custom code for SINGLE mode
    private String codePrefix; // Prefix for BULK mode
    private Integer bulkCount; // Quantity of vouchers for BULK mode
    private String terms;
    private DiscountType discountType;
    private Double discountValue;
    private String applyTicketLimitType;
    private Integer ticketLimitCount;
    private Integer maxOrdersPerBuyer;
    private Integer minTicketsPerOrder;
    private Integer maxTicketsPerOrder;
    private ApplyScope applyScope;
    private List<String> applicableShowtimes;
    private String startDate;
    private String endDate;
    private VoucherStatus status;
}
