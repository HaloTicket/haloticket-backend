package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VenueResponse {
    private Long id;
    private String name;
    private String address;
    private String city;
    private String ward;
    private Integer capacity;
    private String type;
    private Boolean verified;
    private Integer eventsHosted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
