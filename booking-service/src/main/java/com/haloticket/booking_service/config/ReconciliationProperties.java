package com.haloticket.booking_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Cấu hình {@code booking.reconcile}: admin đối soát đơn hàng. */
@Data
@Component
@ConfigurationProperties(prefix = "booking.reconcile")
public class ReconciliationProperties {
    /** Đơn PAID mới hơn số phút này chưa bị đánh dấu lệch vì việc phát hành vé cần một khoảng thời gian. */
    private int paidGraceMinutes = 10;
    /** Số đơn kiểm tra mỗi lần gọi ticket-service (ticket-service nhận tối đa 500). */
    private int batchSize = 200;
    /** Số lô kiểm tra mỗi request, để một request không biến thành quét toàn bảng. */
    private int maxBatches = 5;
    /** Chỉ kiểm tra các đơn đã thanh toán trong số ngày này. */
    private int lookbackDays = 14;
    /** Số dòng đọc từ đối soát tồn kho của ticket-service. */
    private int stockLimit = 200;
}
