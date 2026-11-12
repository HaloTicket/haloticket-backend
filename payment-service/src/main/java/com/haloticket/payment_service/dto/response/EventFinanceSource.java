package com.haloticket.payment_service.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class EventFinanceSource {
    private String id;
    private String title;
    private String bannerUrl;
    private String thumbnailUrl;
    private LocalDateTime endTime;
    private String eventStatus;
    private Organizer organizer;
    @Data public static class Organizer { private String userId; }
}
