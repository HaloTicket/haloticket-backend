package com.haloticket.event_service.dto.request;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

@Data
public class EventCreateRequest {
    private String eventId; // id of existing draft event to update
    private String status; // "DRAFT" | "PENDING"
    private String title;
    private String categoryName;
    private String categoryId;
    private String locationType; // "offline" | "online"
    private String locationName;
    private String city;
    private String ward;
    private String address;
    private String thumbnailUrl; // poster 3:4
    private String bannerUrl; // banner 16:9
    private String description;
    private List<String> galleryImages; // event image gallery URLs

    // organizer info
    private String userId;
    private String organizerName;
    private String organizerInfo;
    private String organizerLogo;

    // time schedule
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private LocalDateTime startTime;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private LocalDateTime endTime;

    // showtimes
    private List<EventShowtimeRequest> showtimes;

    // (legacy) tickets list
    private List<TicketCreateRequest> tickets;

    // settings
    private Boolean isPrivate;
    private String confirmationMessage;
    private Boolean enableCustomQuestions;
    private String questionScope;
    private List<CustomQuestionRequest> customQuestions;

    // payment & tax
    private String accountHolder;
    private String accountNumber;
    private String bankName;
    private String bankBranch;
    private String businessType;
    private String taxName;
    private String taxCode;
    private String taxAddress;

    @Data
    public static class EventShowtimeRequest {
        private String id;
        private String name;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        private LocalDateTime startTime;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        private LocalDateTime endTime;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        private LocalDateTime saleStartTime;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        private LocalDateTime saleEndTime;
        private List<TicketCreateRequest> tickets;
    }

    @Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketCreateRequest {
        private String id;
        private String showtimeId;
        private String name;
        private Double price;
        private Boolean isFree;
        private Integer initialQuantity;
        private Integer totalQuantity;
        private Integer minPerOrder;
        private Integer maxPerOrder;
        private String description;
    }

    @Data
    public static class CustomQuestionRequest {
        private String id;
        private String title;
        private String description;
        private String type;
        private Boolean isRequired;
        private List<String> options;
    }
}
