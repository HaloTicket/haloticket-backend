package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.enums.ApplyScope;
import com.haloticket.event_service.enums.DiscountType;
import com.haloticket.event_service.enums.VoucherStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Thông tin một voucher của sự kiện trả về cho client; {@code code} có thể bị che ({@code null}) với người không quản lý voucher. */
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
    private DiscountType discountType;
    private Double discountValue;
    private String applyTicketLimitType;
    private Integer ticketLimitCount;
    private Integer maxOrdersPerBuyer;
    private Integer minTicketsPerOrder;
    private Integer maxTicketsPerOrder;
    private ApplyScope applyScope;
    private List<String> applicableShowtimes;
    private Integer usedCount;
    private String startDate;
    private String endDate;
    private VoucherStatus status;
    private Boolean isActive;
    private String createdAt;
    private String updatedAt;
}
