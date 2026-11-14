package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventCategoryResponse {
    private String id;
    private String name;
    private String description;
    private String status;
    private String slug;
    private String imageUrl;
    private String icon;
    private Integer sortOrder;
}
