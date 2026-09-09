package com.haloticket.ticket_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatLayoutRequest {
    private String eventId;
    private String draftJson;
    private String svgData;
    private String imageUrl;
    private String status; // DRAFT or PUBLISHED
    private List<ZoneDto> zones;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneDto {
        private String id;
        private String name;
        private String ticketTypeId;
        private String shapeType; // RECTANGLE, CIRCLE, POLYGON, PATH_CURVED
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
