package com.haloticket.event_service.client;

import java.util.List;

/** Kết quả payment-service trả về cho báo cáo: danh sách thanh toán và số bản ghi thiếu mốc thời gian xác nhận (không thể xếp vào kỳ nào). */
public record ReportPaymentsResponse(List<ReportPayment> payments, long missingTimestampCount) {}
