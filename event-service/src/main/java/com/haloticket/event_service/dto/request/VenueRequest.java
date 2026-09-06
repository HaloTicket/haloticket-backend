package com.haloticket.event_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String type; // "indoor", "outdoor", "hybrid"
    private Boolean verified;
}
