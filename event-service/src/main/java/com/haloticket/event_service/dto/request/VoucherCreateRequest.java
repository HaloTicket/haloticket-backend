package com.haloticket.event_service.dto.request;

import com.haloticket.event_service.enums.ApplyScope;
import com.haloticket.event_service.enums.DiscountType;
import com.haloticket.event_service.enums.VoucherStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Yêu cầu tạo voucher cho sự kiện, ở chế độ một mã ({@code SINGLE}) hoặc tạo hàng loạt ({@code BULK}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherCreateRequest {
    private String creationMode; // "SINGLE" or "BULK"
    private String title;
    private String code; // mã tự đặt, dùng cho chế độ SINGLE
    private String codePrefix; // tiền tố mã, dùng cho chế độ BULK
    private Integer bulkCount; // số voucher cần tạo, dùng cho chế độ BULK
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
