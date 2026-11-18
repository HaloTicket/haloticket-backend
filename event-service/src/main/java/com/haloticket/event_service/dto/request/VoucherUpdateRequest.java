package com.haloticket.event_service.dto.request;

import com.haloticket.event_service.enums.ApplyScope;
import com.haloticket.event_service.enums.DiscountType;
import com.haloticket.event_service.enums.VoucherStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Yêu cầu cập nhật một voucher đã có: tiêu đề, mã, điều khoản, mức giảm, giới hạn sử dụng và thời gian hiệu lực. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherUpdateRequest {
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
    private String startDate;
    private String endDate;
    private VoucherStatus status;
}
