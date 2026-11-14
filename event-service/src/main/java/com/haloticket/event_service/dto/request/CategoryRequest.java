package com.haloticket.event_service.dto.request;

import com.haloticket.event_service.enums.CategoryStatus;
import lombok.Data;

/** What the admin sends to create or change a category. Fields left out keep their value on an update. */
@Data
public class CategoryRequest {
    private String name;
    private String description;
    private String slug;
    private String imageUrl;
    private String icon;
    private Boolean active;
    private CategoryStatus status;
}
