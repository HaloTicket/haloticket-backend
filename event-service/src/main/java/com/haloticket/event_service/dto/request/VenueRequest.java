package com.haloticket.event_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Dữ liệu tạo hoặc sửa địa điểm tổ chức sự kiện. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VenueRequest {
    private String name;
    private String address;
    private String city;
    private String ward;
    private Integer capacity;
    private String type; // loại địa điểm: "indoor", "outdoor", "hybrid"
    private Boolean verified;
    private Boolean isPublic;
    private String organizerId;
    private String seatmapTemplate;
}
