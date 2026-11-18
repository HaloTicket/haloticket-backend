package com.haloticket.event_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.authz.EventPermissions;
import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.entity.EventShowtime;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.enums.TicketSyncStatus;
import com.haloticket.event_service.repository.EventCategoryRepository;
import com.haloticket.event_service.repository.EventOrganizerRepository;
import com.haloticket.event_service.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Các giao dịch cơ sở dữ liệu ngắn khi lưu sự kiện. Là bean riêng để {@link Transactional} thật sự có hiệu lực ({@code EventServiceImpl}
 * điều phối nhưng không dùng transaction, và chỉ gọi ticket-service sau khi giao dịch này đã commit).
 */
@Component
@RequiredArgsConstructor
public class EventCatalogWriter {

    /** Sự kiện đã lưu và trạng thái duyệt mà nó có trước lần lưu này. */
    public record Saved(Event event, EventApprovalStatus oldApprovalStatus) {}

    private final EventRepository eventRepository;
    private final EventCategoryRepository eventCategoryRepository;
    private final EventOrganizerRepository eventOrganizerRepository;
    private final EventPermissions eventPermissions;
    private final ObjectMapper objectMapper;

    /** Ai được sửa sự kiện: OWNER hoặc ADMIN của sự kiện (phân công ACTIVE) hoặc admin nền tảng. */
    private boolean mayEdit(Event event, String userId) {
        return eventPermissions.forUser(userId, event.getId(), EventAccessService.isPlatformAdmin())
                .has(Permission.EVENT_EDIT);
    }

    /**
     * Tạo mới hoặc cập nhật sự kiện trong một giao dịch: hồ sơ nhà tổ chức, phân công OWNER cho người tạo, danh mục, thông tin cơ bản, ảnh, suất diễn, bản chụp vé, giá thấp nhất, câu hỏi tùy chỉnh và trạng thái duyệt. Sự kiện đã duyệt khi sửa sẽ chuyển sang {@code UPDATE_PENDING} (hoặc {@code DRAFT} nếu lưu nháp); sự kiện mới hoặc chưa duyệt chuyển {@code PENDING} hay {@code DRAFT} theo {@code status}.
     * @param request dữ liệu sự kiện; có {@code eventId} thì là cập nhật
     * @param uid id người dùng thực hiện
     * @return sự kiện đã lưu cùng trạng thái duyệt trước đó
     * @throws ResponseStatusException 404 {@code EVENT_NOT_FOUND}, 403 {@code EVENT_OWNERSHIP_REQUIRED} khi không có quyền sửa
     * @throws IllegalArgumentException khi thời gian kết thúc không sau thời gian bắt đầu
     */
    @Transactional
    public Saved save(EventCreateRequest request, String uid) {
        Event eventToSave;
        if (request.getEventId() != null && !request.getEventId().isBlank()) {
            eventToSave = eventRepository.findById(request.getEventId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
            if (!mayEdit(eventToSave, uid)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EVENT_OWNERSHIP_REQUIRED");
            }
        } else {
            eventToSave = new Event();
        }

        // tạo mới hoặc cập nhật hồ sơ nhà tổ chức
        List<EventOrganizer> organizers = new ArrayList<>();
        EventOrganizer organizer = eventOrganizerRepository
                .findFirstByUserIdOrderByCreatedAtDesc(uid)
                .orElseGet(() -> EventOrganizer.builder()
                        .userId(uid)
                        .name(request.getOrganizerName())
                        .description(request.getOrganizerInfo())
                        .avatarUrl(request.getOrganizerLogo())
                        .build());

        organizer.setUserId(uid);
        if (request.getOrganizerName() != null && !request.getOrganizerName().trim().isEmpty()) {
            organizer.setName(request.getOrganizerName());
        }
        if (request.getOrganizerInfo() != null)
            organizer.setDescription(request.getOrganizerInfo());
        if (request.getOrganizerLogo() != null)
            organizer.setAvatarUrl(request.getOrganizerLogo());

        organizer = eventOrganizerRepository.save(organizer);
        organizers.add(organizer);

        // tự tạo phân công OWNER cho người tạo, trong cùng lần lưu với sự kiện
        if (eventToSave.getId() == null) {
            StaffAssignment staff = StaffAssignment.builder()
                    .event(eventToSave)
                    .userId(uid)
                    .name(request.getOrganizerName())
                    .role(StaffRole.OWNER)
                    .status(StaffAssignmentStatus.ACTIVE)
                    .build();
            if (eventToSave.getStaffAssignments() == null) {
                eventToSave.setStaffAssignments(new ArrayList<>());
            }
            eventToSave.getStaffAssignments().add(staff);
        }

        // tìm danh mục
        EventCategory category = null;
        if (request.getCategoryId() != null) {
            category = eventCategoryRepository.findById(request.getCategoryId()).orElse(null);
        }
        if (category == null && request.getCategoryName() != null) {
            category = eventCategoryRepository.findByNameContainingIgnoreCase(request.getCategoryName()).orElse(null);
        }

        String eventTitle = (request.getTitle() != null && !request.getTitle().trim().isEmpty())
                ? request.getTitle()
                : "";

        eventToSave.setTitle(eventTitle);
        eventToSave.setDescription(request.getDescription());
        eventToSave.setLocationType(request.getLocationType());
        eventToSave.setLocationName(request.getLocationName());
        eventToSave.setCity(request.getCity());
        eventToSave.setWard(request.getWard());
        eventToSave.setAddress(request.getAddress());
        if (request.getThumbnailUrl() != null)
            eventToSave.setThumbnailUrl(request.getThumbnailUrl());
        if (request.getBannerUrl() != null)
            eventToSave.setBannerUrl(request.getBannerUrl());
        if (request.getGalleryImages() != null) {
            if (eventToSave.getGalleryImages() == null) {
                eventToSave.setGalleryImages(new ArrayList<>(request.getGalleryImages()));
            } else {
                eventToSave.getGalleryImages().clear();
                eventToSave.getGalleryImages().addAll(request.getGalleryImages());
            }
        }
        LocalDateTime startEvt = request.getStartTime() != null ? request.getStartTime()
                : LocalDateTime.now().plusDays(30);
        LocalDateTime endEvt = request.getEndTime() != null ? request.getEndTime()
                : LocalDateTime.now().plusDays(30).plusHours(4);
        if (endEvt.isBefore(startEvt) || endEvt.isEqual(startEvt)) {
            throw new IllegalArgumentException(
                    "Thời gian kết thúc sự kiện phải diễn ra sau thời gian bắt đầu sự kiện!");
        }
        eventToSave.setStartTime(startEvt);
        eventToSave.setEndTime(endEvt);
        eventToSave.setIsPrivate(request.getIsPrivate());
        eventToSave.setConfirmationMessage(request.getConfirmationMessage());
        eventToSave.setEnableCustomQuestions(request.getEnableCustomQuestions());
        eventToSave.setQuestionScope(request.getQuestionScope());

        if (request.getShowtimes() != null && !request.getShowtimes().isEmpty()) {
            if (eventToSave.getShowtimes() == null) {
                eventToSave.setShowtimes(new ArrayList<>());
            }

            Map<String, EventShowtime> existingShowtimes = new java.util.HashMap<>();
            for (EventShowtime st : eventToSave.getShowtimes()) {
                existingShowtimes.put(st.getId(), st);
            }

            List<EventShowtime> updatedShowtimes = new ArrayList<>();

            for (EventCreateRequest.EventShowtimeRequest stReq : request.getShowtimes()) {
                EventShowtime st = null;
                if (stReq.getId() != null && existingShowtimes.containsKey(stReq.getId())) {
                    st = existingShowtimes.get(stReq.getId());
                } else {
                    // không khớp id thì dự phòng so khớp theo tên
                    for (EventShowtime existing : existingShowtimes.values()) {
                        if (existing.getName() != null && existing.getName().equals(stReq.getName())) {
                            st = existing;
                            break;
                        }
                    }
                }

                if (st != null) {
                    st.setName(stReq.getName());
                    st.setStartTime(stReq.getStartTime() != null ? stReq.getStartTime() : startEvt);
                    st.setEndTime(stReq.getEndTime() != null ? stReq.getEndTime() : endEvt);
                    st.setSaleStartTime(stReq.getSaleStartTime());
                    st.setSaleEndTime(stReq.getSaleEndTime());
                } else {
                    st = EventShowtime.builder()
                            .event(eventToSave)
                            .name(stReq.getName())
                            .startTime(stReq.getStartTime() != null ? stReq.getStartTime() : startEvt)
                            .endTime(stReq.getEndTime() != null ? stReq.getEndTime() : endEvt)
                            .saleStartTime(stReq.getSaleStartTime())
                            .saleEndTime(stReq.getSaleEndTime())
                            .build();
                }
                updatedShowtimes.add(st);
            }
            eventToSave.getShowtimes().clear();
            eventToSave.getShowtimes().addAll(updatedShowtimes);
        }
        Double minTicketPrice = null;
        boolean flatShowtimeTickets = false;
        try {
            if (request.getTickets() != null && !request.getTickets().isEmpty()) {
                eventToSave.setTicketsData(objectMapper.writeValueAsString(request.getTickets()));
                for (EventCreateRequest.TicketCreateRequest t : request.getTickets()) {
                    if (t != null && t.getPrice() != null) {
                        if (minTicketPrice == null || t.getPrice() < minTicketPrice) {
                            minTicketPrice = t.getPrice();
                        }
                    }
                }
            } else if (request.getShowtimes() != null) {
                List<EventCreateRequest.TicketCreateRequest> allStTickets = new ArrayList<>();
                for (EventCreateRequest.EventShowtimeRequest st : request.getShowtimes()) {
                    if (st.getTickets() != null && !st.getTickets().isEmpty()) {
                        allStTickets.addAll(st.getTickets());
                        for (EventCreateRequest.TicketCreateRequest t : st.getTickets()) {
                            if (t != null && t.getPrice() != null) {
                                if (minTicketPrice == null || t.getPrice() < minTicketPrice) {
                                    minTicketPrice = t.getPrice();
                                }
                            }
                        }
                    }
                }
                if (!allStTickets.isEmpty()) {
                    eventToSave.setTicketsData(objectMapper.writeValueAsString(allStTickets));
                    flatShowtimeTickets = true;
                }
            }
        } catch (Exception e) {
            System.out.println("DEBUG: Exception serializing tickets: " + e.getMessage());
        }
        if (minTicketPrice != null) {
            eventToSave.setMinPrice(minTicketPrice);
        }

        try {
            if (request.getCustomQuestions() != null) {
                eventToSave.setCustomQuestionsData(objectMapper.writeValueAsString(request.getCustomQuestions()));
            }
        } catch (Exception e) {
            System.out.println("DEBUG: Exception serializing questions: " + e.getMessage());
        }

        EventApprovalStatus oldApprovalStatus = eventToSave.getEventApprovalStatus();

        if (oldApprovalStatus == EventApprovalStatus.APPROVED || oldApprovalStatus == EventApprovalStatus.UPDATE_PENDING) {
            boolean isDraft = "DRAFT".equalsIgnoreCase(request.getStatus());
            if (isDraft) {
                eventToSave.setEventStatus(EventStatus.DRAFT);
                eventToSave.setEventApprovalStatus(EventApprovalStatus.DRAFT);
            } else {
                eventToSave.setEventStatus(EventStatus.APPROVED);
                eventToSave.setEventApprovalStatus(EventApprovalStatus.UPDATE_PENDING);
            }
        } else if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            boolean isDraft = "DRAFT".equalsIgnoreCase(request.getStatus());
            eventToSave.setEventStatus(isDraft ? EventStatus.DRAFT : EventStatus.PENDING);
            eventToSave.setEventApprovalStatus(isDraft ? EventApprovalStatus.DRAFT : EventApprovalStatus.PENDING);
        } else if (eventToSave.getEventStatus() == null) {
            eventToSave.setEventStatus(EventStatus.DRAFT);
            eventToSave.setEventApprovalStatus(EventApprovalStatus.DRAFT);
        }

        eventToSave.setCategory(category);
        eventToSave.setOrganizers(organizers);

        Event saved = eventRepository.save(eventToSave);

        if (flatShowtimeTickets) {
            stampShowtimeIds(saved, request);
        }
        // đọc sẵn mọi dữ liệu response cần: response được dựng sau khi giao dịch này đã kết thúc
        if (saved.getShowtimes() != null) saved.getShowtimes().size();
        if (saved.getGalleryImages() != null) saved.getGalleryImages().size();
        if (saved.getOrganizers() != null) saved.getOrganizers().size();
        return new Saved(saved, oldApprovalStatus);
    }

    /**
     * Bản chụp vé riêng của từng suất diễn là một danh sách phẳng; mỗi vé nay mang thêm id suất diễn để lần đồng bộ lại có thể
     * gửi cho mỗi suất đúng vé của nó. Suất được xác định bằng cách so tên với các suất đã lưu.
     */
    private void stampShowtimeIds(Event saved, EventCreateRequest request) {
        if (saved.getShowtimes() == null) return;
        List<EventCreateRequest.TicketCreateRequest> flat = new ArrayList<>();
        for (EventCreateRequest.EventShowtimeRequest stReq : request.getShowtimes()) {
            if (stReq.getTickets() == null || stReq.getTickets().isEmpty()) continue;
            String showtimeId = null;
            for (EventShowtime st : saved.getShowtimes()) {
                if (st.getName() != null && st.getName().equals(stReq.getName())) {
                    showtimeId = st.getId();
                    break;
                }
            }
            for (EventCreateRequest.TicketCreateRequest t : stReq.getTickets()) {
                EventCreateRequest.TicketCreateRequest copy = TicketPayloads.copy(t);
                if (showtimeId != null) copy.setShowtimeId(showtimeId);
                flat.add(copy);
            }
        }
        try {
            if (!flat.isEmpty()) saved.setTicketsData(objectMapper.writeValueAsString(flat));
        } catch (Exception e) {
            System.out.println("DEBUG: Exception serializing tickets: " + e.getMessage());
        }
    }

    /**
     * Giao dịch ngắn ghi kết quả đồng bộ vé lên sự kiện. Thành công thì xóa lỗi và ghi thời điểm đồng bộ; thất bại thì lưu mã lỗi (cắt 64 ký tự).
     *
     * @param eventId id sự kiện; không tồn tại thì không làm gì
     * @param status kết quả đồng bộ
     * @param error mã lỗi khi thất bại, có thể null
     */
    @Transactional
    public void markSync(String eventId, TicketSyncStatus status, String error) {
        eventRepository.findById(eventId).ifPresent(event -> {
            event.setTicketsSyncStatus(status);
            event.setTicketsSyncError(status == TicketSyncStatus.SYNCED ? null : truncate(error));
            event.setTicketsSyncedAt(status == TicketSyncStatus.SYNCED ? LocalDateTime.now() : event.getTicketsSyncedAt());
            eventRepository.save(event);
        });
    }

    /** Cắt chuỗi còn tối đa 64 ký tự; null giữ nguyên. */
    private static String truncate(String s) {
        return s == null || s.length() <= 64 ? s : s.substring(0, 64);
    }
}
