package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VenueSeatmapsResponse {
    private Long venueId;
    private String venueName;
    private String adminTemplate;
    private List<OrganizerSeatmapDto> organizerSeatmaps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrganizerSeatmapDto {
        private String eventId;
        private String eventTitle;
        private String organizerName;
        private LocalDateTime updatedAt;
        private String draftJson;
        private Integer capacity;
        private Integer zonesCount;
    }
}
