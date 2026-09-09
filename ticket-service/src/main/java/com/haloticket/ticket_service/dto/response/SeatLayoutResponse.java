package com.haloticket.ticket_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatLayoutResponse {
    private String id;
    private String eventId;
    private String draftJson;
    private String svgData;
    private String imageUrl;
    private String status;
    private List<ZoneResponseDto> zones;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneResponseDto {
        private String id;
        private String name;
        private String ticketTypeId;
        private String shapeType;
        private String colorCode;
        private String fillColor;
        private String strokeColor;
        private Double curvature;
        private Double x;
        private Double y;
        private Double width;
        private Double height;
        private Double rotation;
        private String points;
        private Integer capacity;
    }
}
