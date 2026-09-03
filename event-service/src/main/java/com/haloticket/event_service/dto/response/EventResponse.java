package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.enums.EventStatus;
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
public class EventResponse {
    private String id;
    private String title;
    private String description;
    private String descriptionText;
    private String bannerUrl;
    private String thumbnailUrl;
    private String posterImage;
    private String locationName;
    private String address;
    private String author;
    private String director;
    private List<String> cast;
    private List<String> galleryImages;
    private String datesSummary;
    private Integer extraDaysCount;
    private Double minPrice;
    private EventStatus eventStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String categoryId;
    private String categoryName;
    private OrganizerDto organizer;
}
