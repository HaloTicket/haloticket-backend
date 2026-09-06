package com.haloticket.event_service.dto.request;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class EventCreateRequest {
    private String eventId; // ID of existing draft event to update
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
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    // tickets list
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
    public static class TicketCreateRequest {
        private String name;
        private Double price;
        private Boolean isFree;
        private Integer totalQuantity;
        private Integer minPerOrder;
        private Integer maxPerOrder;
        private String startSaleDate;
        private String endSaleDate;
        private String description;
    }

    @Data
    public static class CustomQuestionRequest {
        private String title;
        private String description;
        private String type;
        private Boolean isRequired;
    }
}
