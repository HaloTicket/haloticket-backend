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
    private List<String> galleryImages;
    private String datesSummary;
    private Integer extraDaysCount;
    private Double minPrice;
    private EventStatus eventStatus;
    private String eventApprovalStatus;
    private String rejectionReason;
    private String rejectionNotes;
    private String submittedAt;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String categoryId;
    private String categoryName;
    private String locationType;
    private String city;
    private String ward;
    private Boolean isPrivate;
    private String confirmationMessage;
    private Boolean enableCustomQuestions;
    private String questionScope;
    private List<EventShowtimeDto> showtimes;
    private Object tickets;
    private Object customQuestions;
    private String accountHolder;
    private String accountNumber;
    private String bankName;
    private String bankBranch;
    private String businessType;
    private String taxName;
    private String taxAddress;
    private String taxCode;
    private OrganizerDto organizer;
    private List<EventMemberResponse> staffAssignments;
    private TicketsSyncDto ticketsSync;
}
