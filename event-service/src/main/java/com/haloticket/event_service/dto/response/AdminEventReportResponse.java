package com.haloticket.event_service.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Báo cáo sự kiện dành cho quản trị viên: tổng hợp, kỳ trước, đường thời gian, bảng theo sự kiện đã phân trang và các lựa chọn bộ lọc. {@code completeness} và {@code warnings} cho biết dữ liệu có đầy đủ hay thiếu nguồn. */
public record AdminEventReportResponse(LocalDate from, LocalDate to, String timezone, Instant generatedAt,
        String completeness, List<String> warnings, Summary summary, Summary previous,
        List<TimelinePoint> timeline, List<EventRow> rows, int page, int size, long totalElements,
        int totalPages, Options options) {
    /** Số liệu tổng hợp của một kỳ: doanh thu, số đơn, số vé và số sự kiện có bán được vé. */
    public record Summary(BigDecimal revenue, long orders, long tickets, long eventsWithSales) {}
    /** Số liệu của một ngày trên đường thời gian: doanh thu, số đơn và số vé. */
    public record TimelinePoint(LocalDate date, BigDecimal revenue, long orders, long tickets) {}
    /** Một dòng của bảng báo cáo: sự kiện cùng nhà tổ chức, danh mục, trạng thái và các chỉ số bán hàng. */
    public record EventRow(String eventId, String title, String organizer, String category, String status,
            BigDecimal revenue, long orders, long tickets) {}
    /** Một lựa chọn trong bộ lọc: mã và tên hiển thị. */
    public record Option(String id, String name) {}
    /** Tập các lựa chọn bộ lọc của báo cáo: danh mục, nhà tổ chức và trạng thái. */
    public record Options(List<Option> categories, List<Option> organizers, List<String> statuses) {}
}
