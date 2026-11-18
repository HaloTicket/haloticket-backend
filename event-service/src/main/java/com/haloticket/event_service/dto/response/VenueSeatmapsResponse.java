package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** Các sơ đồ chỗ ngồi của một địa điểm: mẫu do quản trị viên đặt và sơ đồ của từng nhà tổ chức. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VenueSeatmapsResponse {
    private Long venueId;
    private String venueName;
    private String adminTemplate;
    private List<OrganizerSeatmapDto> organizerSeatmaps;

    /** Sơ đồ chỗ ngồi của một sự kiện tại địa điểm: bản nháp JSON cùng sức chứa và số khu. */
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
