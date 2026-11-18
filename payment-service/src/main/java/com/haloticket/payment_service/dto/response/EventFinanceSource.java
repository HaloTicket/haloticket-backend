package com.haloticket.payment_service.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

/** Thông tin sự kiện từ event-service dùng cho tính toán tài chính: tên, ảnh, giờ kết thúc, trạng thái và ban tổ chức. */
@Data
public class EventFinanceSource {
    private String id;
    private String title;
    private String bannerUrl;
    private String thumbnailUrl;
    private LocalDateTime endTime;
    private String eventStatus;
    private Organizer organizer;
    /** Ban tổ chức của sự kiện, chỉ giữ mã người dùng. */
    @Data public static class Organizer { private String userId; }
}
