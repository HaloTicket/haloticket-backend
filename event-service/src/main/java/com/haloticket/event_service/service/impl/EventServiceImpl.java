package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.client.NotificationServiceClient;
import com.haloticket.event_service.client.PaymentServiceClient;
import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.client.UserInternalServiceClient;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.EventSearchItem;
import com.haloticket.event_service.dto.response.EventSearchPage;
import com.haloticket.event_service.search.EventSearchCriteria;
import com.haloticket.event_service.search.EventSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import com.haloticket.event_service.dto.response.OrganizerDto;
import com.haloticket.event_service.dto.response.EventMemberResponse;
import com.haloticket.event_service.dto.response.EventShowtimeDto;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.entity.EventShowtime;
import com.haloticket.event_service.entity.SearchLog;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.dto.response.TicketsSyncDto;
import com.haloticket.event_service.enums.TicketSyncStatus;
import com.haloticket.event_service.exception.TicketSyncFailedException;
import com.haloticket.event_service.exception.TicketSyncRejectedException;
import com.haloticket.event_service.service.EventCatalogWriter;
import com.haloticket.event_service.service.TicketPayloads;
import com.haloticket.event_service.service.TicketSyncService.Batch;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.repository.EventCategoryRepository;
import com.haloticket.event_service.repository.EventOrganizerRepository;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.SearchLogRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import com.haloticket.event_service.service.EventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.haloticket.event_service.client.BookingInternalServiceClient;
import com.haloticket.event_service.service.EventCatalogWriter;
import com.haloticket.event_service.service.TicketSyncService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Cài đặt nghiệp vụ sự kiện: lưu sự kiện, đồng bộ vé sang ticket-service, duyệt hoặc từ chối của admin, ghép dữ liệu trả về cho client và phát thông báo qua Kafka hoặc notification-service. */
@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;
    private final EventCategoryRepository eventCategoryRepository;
    private final EventOrganizerRepository eventOrganizerRepository;
    private final SearchLogRepository searchLogRepository;
    private final TicketServiceClient ticketServiceClient;
    private final PaymentServiceClient paymentServiceClient;
    private final UserServiceClient userServiceClient;
    private final UserInternalServiceClient userInternalServiceClient;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final EventCatalogWriter catalogWriter;
    private final TicketSyncService ticketSyncService;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EventServiceImpl.class);

    @Autowired(required = false)
    private NotificationServiceClient notificationServiceClient;

    /** Client lấy số vé đã bán của sự kiện; được hỏi trước khi thu hồi phê duyệt. */
    @Autowired(required = false)
    private BookingInternalServiceClient bookingStatsClient;

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper;

    /**
     * Cố ý không dùng transaction: sự kiện được lưu trong một giao dịch ngắn bởi {@link EventCatalogWriter} (bean riêng), rồi
     * mới gọi ticket-service bên ngoài mọi giao dịch cơ sở dữ liệu. Sau khi lưu, đồng bộ vé, đồng bộ tài khoản nhận tiền sang
     * payment-service và thông báo admin khi sự kiện vừa gửi duyệt hoặc vừa cập nhật.
     *
     * @param request dữ liệu sự kiện; có {@code eventId} thì là cập nhật
     * @param authorization header Authorization của nhà tổ chức
     * @return sự kiện sau khi lưu, null nếu request null
     * @throws ResponseStatusException 403 {@code EKYC_REQUIRED} khi chưa đủ điều kiện nhà tổ chức, 503 {@code EKYC_STATUS_UNAVAILABLE} khi không kiểm tra được
     * @throws TicketSyncFailedException khi đồng bộ vé thất bại (sự kiện vẫn đã được lưu)
     * @throws TicketSyncRejectedException khi ticket-service từ chối nội dung vé
     */
    @Override
    public EventResponse createEvent(EventCreateRequest request, String authorization) {
        if (request == null)
            return null;

        OrganizerEligibilityResponse eligibility = requireEligibleOrganizer(authorization);
        String uid = eligibility.userId();

        EventCatalogWriter.Saved result = catalogWriter.save(request, uid);
        Event saved = result.event();
        EventApprovalStatus oldApprovalStatus = result.oldApprovalStatus();

        // sự kiện có suất diễn chỉ đồng bộ theo từng suất; không có suất diễn thì đồng bộ một lần với showtimeId = null
        List<Batch> batches = TicketPayloads.batches(saved.getShowtimes(), request.getTickets(),
                st -> ownTicketsOf(st, request));
        RuntimeException syncFailure = null;
        try {
            syncAndMark(saved, batches);
        } catch (TicketSyncFailedException | TicketSyncRejectedException e) {
            syncFailure = e; // sự kiện vẫn được lưu và phần còn lại vẫn chạy; lỗi chỉ được ném cho bên gọi ở cuối
        }
        syncPayoutAccountToPaymentService(uid, request, authorization);

        boolean isNewlySubmitted = (oldApprovalStatus == null || oldApprovalStatus == EventApprovalStatus.DRAFT)
                && saved.getEventApprovalStatus() == EventApprovalStatus.PENDING;
        boolean isUpdatedApproval = (oldApprovalStatus == EventApprovalStatus.APPROVED || oldApprovalStatus == EventApprovalStatus.UPDATE_PENDING)
                && saved.getEventApprovalStatus() == EventApprovalStatus.UPDATE_PENDING;

        if (isNewlySubmitted) {
            publishNotification("ADMIN", "NEW_EVENT_APPROVAL", "Sự kiện mới cần duyệt",
                    "Sự kiện \"" + saved.getTitle() + "\" vừa được gửi yêu cầu phê duyệt.");
        } else if (isUpdatedApproval) {
            publishNotification("ADMIN", "EVENT_UPDATED", "Sự kiện cập nhật thông tin",
                    "Sự kiện \"" + saved.getTitle() + "\" đã được Ban tổ chức cập nhật thông tin và cần xem xét.");
        }

        if (syncFailure != null) throw syncFailure;
        return mapToEventResponse(saved);
    }

    /**
     * Cập nhật sự kiện có sẵn bằng cách gán {@code eventId} vào request rồi dùng lại luồng của {@link #createEvent}.
     * @param id id sự kiện cần cập nhật
     * @param request dữ liệu mới, null được coi như request rỗng
     * @param authorization header Authorization của nhà tổ chức
     * @return sự kiện sau khi lưu
     */
    @Override
    public EventResponse updateEvent(String id, EventCreateRequest request, String authorization) {
        if (request == null) {
            request = new EventCreateRequest();
        }
        request.setEventId(id);
        return createEvent(request, authorization);
    }

    /** Gửi lại bản chụp {@code ticketsData} đã lưu sang ticket-service (ở đó là upsert). Quyền do nơi gọi kiểm tra. Ném 409 {@code NO_TICKETS_TO_SYNC} khi không có vé nào để gửi. */
    @Override
    public EventResponse resyncTickets(String eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
        List<EventCreateRequest.TicketCreateRequest> snapshot = parseSnapshot(event.getTicketsData());
        List<EventShowtime> showtimes = event.getShowtimes();
        List<EventCreateRequest.TicketCreateRequest> eventLevel = snapshot;
        if (showtimes != null && !showtimes.isEmpty()) {
            java.util.Set<String> ids = showtimes.stream().map(EventShowtime::getId).collect(Collectors.toSet());
            eventLevel = snapshot.stream()
                    .filter(t -> t.getShowtimeId() == null || t.getShowtimeId().isBlank() || !ids.contains(t.getShowtimeId()))
                    .collect(Collectors.toList());
        }
        List<Batch> batches = TicketPayloads.batches(showtimes, eventLevel,
                st -> snapshot.stream().filter(t -> st.getId() != null && st.getId().equals(t.getShowtimeId()))
                        .collect(Collectors.toList()));
        if (batches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "NO_TICKETS_TO_SYNC");
        }
        syncAndMark(event, batches);
        return mapToEventResponse(event);
    }

    /** Đọc bản chụp vé lưu dạng JSON của sự kiện; JSON hỏng hoặc rỗng thì trả danh sách rỗng. */
    private List<EventCreateRequest.TicketCreateRequest> parseSnapshot(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<EventCreateRequest.TicketCreateRequest> parsed = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<EventCreateRequest.TicketCreateRequest>>() {});
            // bản chụp cũ có thể chứa dòng giống suất diễn (không có tên vé): đó không phải vé
            return parsed.stream().filter(t -> t != null && t.getName() != null).collect(Collectors.toList());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Tìm vé riêng của một suất diễn trong request bằng cách so tên suất; trả null nếu suất không khai báo vé riêng. */
    private List<EventCreateRequest.TicketCreateRequest> ownTicketsOf(EventShowtime st, EventCreateRequest request) {
        if (request.getShowtimes() == null || st.getName() == null) return null;
        for (EventCreateRequest.EventShowtimeRequest stReq : request.getShowtimes()) {
            if (st.getName().equals(stReq.getName())) return stReq.getTickets();
        }
        return null;
    }

    /** Đồng bộ rồi ghi {@code SYNCED} hoặc {@code FAILED} lên sự kiện (trong bộ nhớ và trong một giao dịch ngắn). Không có lô nào để gửi thì giữ nguyên trạng thái. */
    private void syncAndMark(Event event, List<Batch> batches) {
        if (batches.isEmpty()) return;
        try {
            ticketSyncService.syncAll(event.getId(), batches);
        } catch (TicketSyncRejectedException rejected) {
            mark(event, TicketSyncStatus.FAILED, rejected.getCode());
            throw rejected;
        } catch (TicketSyncFailedException failed) {
            mark(event, TicketSyncStatus.FAILED, failed.getErrorCode());
            throw failed;
        }
        mark(event, TicketSyncStatus.SYNCED, null);
    }

    /** Ghi trạng thái đồng bộ vé lên đối tượng sự kiện và lưu qua {@code catalogWriter}; lỗi khi lưu chỉ ghi log. */
    private void mark(Event event, TicketSyncStatus status, String error) {
        event.setTicketsSyncStatus(status);
        event.setTicketsSyncError(status == TicketSyncStatus.SYNCED ? null : error);
        if (status == TicketSyncStatus.SYNCED) event.setTicketsSyncedAt(LocalDateTime.now());
        try {
            catalogWriter.markSync(event.getId(), status, error);
        } catch (Exception e) {
            log.warn("Could not record the ticket sync status of event {}", event.getId() == null ? null : event.getId().substring(0, Math.min(8, event.getId().length())));
        }
    }

    /**
     * Kiểm tra với user-service rằng người gọi đủ điều kiện làm nhà tổ chức.
     * @throws ResponseStatusException 403 {@code EKYC_REQUIRED} khi chưa đủ điều kiện, 503 {@code EKYC_STATUS_UNAVAILABLE} khi không gọi được user-service
     */
    private OrganizerEligibilityResponse requireEligibleOrganizer(String authorization) {
        try {
            OrganizerEligibilityResponse eligibility = userServiceClient.getOrganizerEligibility(authorization);
            if (eligibility == null || !eligibility.eligible() || eligibility.userId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EKYC_REQUIRED");
            }
            return eligibility;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_STATUS_UNAVAILABLE");
        }
    }

    /** Người được báo về kết quả duyệt: OWNER đang hoạt động trong phân công của sự kiện; không có thì dùng giá trị mặc định {@code user_default_id}. */
    private String recipientOf(Event event) {
        return staffAssignmentRepository.findByEventId(event.getId()).stream()
                .filter(a -> a.getRole() == com.haloticket.event_service.enums.StaffRole.OWNER
                        && a.getStatus() == StaffAssignmentStatus.ACTIVE && a.getUserId() != null)
                .map(StaffAssignment::getUserId).findFirst().orElse("user_default_id");
    }


    /** Đồng bộ tài khoản nhận tiền của nhà tổ chức sang payment-service khi request có chủ tài khoản. Lỗi chỉ in cảnh báo, không làm hỏng việc lưu sự kiện. */
    private void syncPayoutAccountToPaymentService(String userId, EventCreateRequest request, String authorization) {
        if (userId == null || request.getAccountHolder() == null || request.getAccountHolder().trim().isEmpty())
            return;
        try {
            OrganizerPayoutAccountRequest payoutReq = OrganizerPayoutAccountRequest.builder()
                    .organizerUserId(userId)
                    .accountHolder(request.getAccountHolder())
                    .accountNumber(request.getAccountNumber())
                    .bankName(request.getBankName())
                    .bankBranch(request.getBankBranch())
                    .businessType(request.getBusinessType())
                    .taxName(request.getTaxName())
                    .taxCode(request.getTaxCode())
                    .taxAddress(request.getTaxAddress())
                    .build();

            paymentServiceClient.saveOrUpdatePayoutAccount(authorization, payoutReq);
        } catch (Exception e) {
            System.err.println("Warning: Could not sync payout account to payment-service: " + e.getMessage());
        }
    }

    /**
     * Lấy danh sách loại vé của sự kiện từ ticket-service (ưu tiên danh sách đầy đủ, nếu rỗng thì lấy danh sách đang bán).
     * @param eventId id sự kiện
     * @param unavailable mảng một phần tử, được đặt true khi không gọi được ticket-service
     * @return danh sách vé hoặc null
     */
    private Object fetchTicketsFromTicketService(String eventId, boolean[] unavailable) {
        if (eventId == null)
            return null;
        try {
            Object all = ticketServiceClient.getAllTicketTypesByEventId(eventId);
            if (all instanceof List<?> list && !list.isEmpty()) {
                return all;
            }
            return ticketServiceClient.getTicketTypesByEventId(eventId);
        } catch (Exception e) {
            unavailable[0] = true;
            return null;
        }
    }

    /** Lấy hồ sơ nhà tổ chức tạo gần nhất của người gọi. Mọi lỗi (chưa đủ điều kiện, không gọi được dịch vụ) đều trả null. */
    @Override
    public OrganizerDto getOrganizerProfile(String authorization) {
        try {
            String userId = requireEligibleOrganizer(authorization).userId();
            return eventOrganizerRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                    .map(org -> OrganizerDto.builder()
                            .id(org.getId())
                            .name(org.getName())
                            .avatar(org.getAvatarUrl())
                            .description(org.getDescription())
                            .build())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lấy danh sách sự kiện công khai (đã duyệt và không riêng tư), mỗi sự kiện được ghép thêm vé từ ticket-service. Bản nháp, chờ duyệt, bị
     * từ chối và sự kiện riêng tư không có trong danh sách này; người tổ chức xem sự kiện của mình qua {@link #getMyEvents(String)}.
     */
    @Override
    public List<EventResponse> getAllEvents() {
        return eventRepository.findPublicEvents().stream()
                .map(this::mapToEventResponse)
                .collect(Collectors.toList());
    }

    /**
     * Tìm kiếm sự kiện công khai ở phía máy chủ: chỉ sự kiện đã duyệt và không riêng tư, lọc theo từ khóa không dấu, thành phố, danh mục,
     * khoảng ngày và miễn phí, sắp xếp rồi phân trang. Kết quả gọn (không gọi ticket-service cho từng sự kiện).
     *
     * @param criteria tiêu chí tìm kiếm (số trang và kích thước được giới hạn trong khoảng hợp lệ)
     * @return một trang kết quả
     */
    @Override
    @Transactional(readOnly = true)
    public EventSearchPage searchEvents(EventSearchCriteria criteria) {
        EventSearchCriteria safe = criteria.normalized();
        Page<Event> page = eventRepository.findAll(EventSpecifications.forSearch(safe, LocalDateTime.now()), PageRequest.of(safe.page(), safe.size()));
        List<EventSearchItem> items = page.getContent().stream().map(event -> new EventSearchItem(
                event.getId(), event.getTitle(), event.getBannerUrl(), event.getThumbnailUrl(), event.getLocationName(),
                event.getAddress(), event.getCity(),
                event.getCategory() != null ? event.getCategory().getId() : null,
                event.getCategory() != null ? event.getCategory().getName() : null,
                event.getStartTime(), event.getEndTime(), event.getSaleStartTime(), event.getSaleEndTime(), event.getMinPrice())).toList();
        return new EventSearchPage(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /** Lấy các sự kiện mà người gọi có phân công ACTIVE (mọi vai trò). Không xác định được người dùng thì trả danh sách rỗng. */
    @Override
    public List<EventResponse> getMyEvents(String authorization) {
        try {
            OrganizerEligibilityResponse eligibility = userServiceClient.getOrganizerEligibility(authorization);
            if (eligibility != null && eligibility.userId() != null) {
                return eventRepository.findMyEvents(eligibility.userId()).stream()
                        .map(this::mapToEventResponse)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.out.println("DEBUG: Failed to get user eligibility for my-events: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /** Lấy chi tiết sự kiện theo id; trả về null khi không có sự kiện đó (controller trả 404). Không được thay bằng sự kiện khác. */
    @Override
    public EventResponse getEventById(String id) {
        return eventRepository.findById(id)
                .map(this::mapToEventResponse)
                .orElse(null);
    }

    /** Lấy các danh mục theo thứ tự hiển thị (vị trí rồi tên). */
    @Override
    public List<EventCategoryResponse> getAllCategories() {
        return eventCategoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(this::mapToCategoryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Lấy các từ khóa được tìm nhiều nhất. Khi chưa có dữ liệu thì trả một danh sách từ khóa gợi ý cố định.
     * @param limit số từ khóa tối đa
     */
    @Override
    public List<String> getTopSearchedKeywords(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<String> topKeywords = searchLogRepository.findTopSearchedKeywords(pageable);
        if (topKeywords == null || topKeywords.isEmpty()) {
            return List.of("idecaf", "xoay tròn", "Pickleball World Cup");
        }
        return topKeywords;
    }

    /** Ghi nhận lượt tìm kiếm: chuẩn hóa từ khóa về chữ thường, tạo dòng mới nếu chưa có và tăng bộ đếm. Từ khóa rỗng bị bỏ qua. */
    @Override
    public void logSearchQuery(String query) {
        if (query == null || query.trim().isEmpty())
            return;
        String cleanQuery = query.trim().toLowerCase();
        SearchLog searchLog = searchLogRepository.findByQueryTermIgnoreCase(cleanQuery)
                .orElseGet(() -> SearchLog.builder()
                        .queryTerm(cleanQuery)
                        .searchCount(0L)
                        .build());
        searchLog.setSearchCount(searchLog.getSearchCount() + 1);
        searchLogRepository.save(searchLog);
    }

    /** Dựng response đầy đủ của sự kiện: nhà tổ chức, suất diễn, câu hỏi tùy chỉnh, danh sách vé (ưu tiên ticket-service, dự phòng bản chụp cục bộ, bỏ vé ẩn), giá thấp nhất và trạng thái đồng bộ vé. Nếu giá thấp nhất tính được khác giá đang lưu thì cập nhật lại vào sự kiện. */
    private EventResponse mapToEventResponse(Event event) {
        if (event == null)
            return null;

        OrganizerDto organizerDto = null;
        if (event.getOrganizers() != null && !event.getOrganizers().isEmpty()) {
            EventOrganizer org = event.getOrganizers().get(0);
            organizerDto = OrganizerDto.builder()
                    .id(org.getId())
                    .userId(org.getUserId())
                    .name(org.getName() != null ? org.getName() : "Halo Entertainment")
                    .avatar(org.getAvatarUrl() != null ? org.getAvatarUrl()
                            : "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200&q=80")
                    .description(org.getDescription())
                    .build();
        }

        String poster = event.getBannerUrl() != null ? event.getBannerUrl() : event.getThumbnailUrl();

        // lấy vé động từ ticket-service, nếu không được thì dùng JSON lưu cục bộ
        boolean[] ticketsUnavailable = new boolean[1];
        Object ticketsObj = fetchTicketsFromTicketService(event.getId(), ticketsUnavailable);
        if (ticketsObj instanceof List<?> list) {
            List<Object> activeTickets = new ArrayList<>();
            for (Object t : list) {
                if (t instanceof Map<?, ?> tMap) {
                    Object status = tMap.get("ticketTypeStatus");
                    if ("HIDDEN".equalsIgnoreCase(String.valueOf(status))) continue;
                    activeTickets.add(t);
                } else {
                    activeTickets.add(t);
                }
            }
            ticketsObj = activeTickets;
        }
        if ((ticketsObj == null || (ticketsObj instanceof List && ((List<?>) ticketsObj).isEmpty()))
                && event.getTicketsData() != null && !event.getTicketsData().trim().isEmpty()) {
            try {
                Object parsed = objectMapper.readValue(event.getTicketsData(), Object.class);
                if (parsed instanceof List<?> list) {
                    List<Object> validTickets = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            boolean isStaleShowtime = map.containsKey("startTime") && !map.containsKey("price");
                            if (!isStaleShowtime) {
                                validTickets.add(item);
                            }
                        } else {
                            validTickets.add(item);
                        }
                    }
                    ticketsObj = validTickets.isEmpty() ? null : validTickets;
                } else {
                    ticketsObj = parsed;
                }
            } catch (Exception e) {
                ticketsObj = event.getTicketsData();
            }
        }

        Object customQuestionsObj = null;
        if (event.getCustomQuestionsData() != null && !event.getCustomQuestionsData().trim().isEmpty()) {
            try {
                customQuestionsObj = objectMapper.readValue(event.getCustomQuestionsData(), Object.class);
            } catch (Exception e) {
                customQuestionsObj = event.getCustomQuestionsData();
            }
        }

        List<EventShowtimeDto> showtimesDto = null;
        if (event.getShowtimes() != null && !event.getShowtimes().isEmpty()) {
            showtimesDto = event.getShowtimes().stream()
                    .map(st -> EventShowtimeDto.builder()
                            .id(st.getId())
                            .name(st.getName())
                            .startTime(st.getStartTime())
                            .endTime(st.getEndTime())
                            .saleStartTime(st.getSaleStartTime())
                            .saleEndTime(st.getSaleEndTime())
                            .build())
                    .collect(Collectors.toList());
        }

        Double computedMinPrice = null;
        if (ticketsObj instanceof List<?> list && !list.isEmpty()) {
            Double min = null;
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object p = map.get("price");
                    if (p != null) {
                        try {
                            double val = Double.parseDouble(p.toString());
                            if (min == null || val < min) min = val;
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (min != null) {
                computedMinPrice = min;
            }
        }
        if (computedMinPrice == null) {
            computedMinPrice = event.getMinPrice();
        } else if (event.getMinPrice() == null || !computedMinPrice.equals(event.getMinPrice())) {
            event.setMinPrice(computedMinPrice);
            try {
                eventRepository.save(event);
            } catch (Exception ignored) {}
        }

        return EventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .description(event.getDescription())
                .descriptionText(event.getDescription())
                .bannerUrl(event.getBannerUrl())
                .thumbnailUrl(event.getThumbnailUrl())
                .posterImage(poster)
                .locationType(event.getLocationType())
                .locationName(event.getLocationName())
                .city(event.getCity())
                .ward(event.getWard())
                .address(event.getAddress())
                .galleryImages(event.getGalleryImages())
                .datesSummary(event.getDatesSummary())
                .extraDaysCount(event.getExtraDaysCount() != null ? event.getExtraDaysCount() : 5)
                .minPrice(computedMinPrice)
                .eventStatus(event.getEventStatus())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .categoryId(event.getCategory() != null ? event.getCategory().getId() : null)
                .categoryName(event.getCategory() != null ? event.getCategory().getName() : null)
                .eventApprovalStatus(
                        event.getEventApprovalStatus() != null ? event.getEventApprovalStatus().name() : "PENDING")
                .rejectionReason(event.getRejectionReason())
                .rejectionNotes(event.getRejectionNotes())
                .submittedAt(event.getCreatedAt() != null ? event.getCreatedAt().toString() : "Mới gửi")
                .isPrivate(event.getIsPrivate())
                .confirmationMessage(event.getConfirmationMessage())
                .enableCustomQuestions(event.getEnableCustomQuestions())
                .questionScope(event.getQuestionScope())
                .showtimes(showtimesDto)
                .tickets(ticketsObj)
                .customQuestions(customQuestionsObj)
                .organizer(organizerDto)
                .ticketsSync(ticketsUnavailable[0]
                        // không đọc được ticket-service: danh sách vé ở trên (nếu có) chỉ là bản chụp cục bộ, không phải dữ liệu đã lưu thật
                        ? TicketsSyncDto.builder().status("UNAVAILABLE").error("TICKET_SERVICE_UNAVAILABLE")
                                .syncedAt(event.getTicketsSyncedAt()).build()
                        : TicketsSyncDto.builder()
                                .status(event.getTicketsSyncStatus() != null ? event.getTicketsSyncStatus().name() : null)
                                .error(event.getTicketsSyncError()).syncedAt(event.getTicketsSyncedAt()).build())
                .staffAssignments(staffAssignmentRepository.findByEventId(event.getId()).stream()
                        .map(a -> EventMemberResponse.builder()
                                .id(a.getId())
                                .name(a.getName())
                                .email(a.getEmail())
                                .role(a.getRole())
                                .status(a.getStatus().name())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    /** Lấy sự kiện theo trạng thái duyệt (không phân biệt hoa thường, {@code ALL} hoặc null là tất cả), sắp mới nhất trước. */
    @Override
    public List<EventResponse> getApprovalEvents(String status) {
        List<Event> events = eventRepository.findAll();
        if (status != null && !"ALL".equalsIgnoreCase(status.trim())) {
            events = events.stream()
                    .filter(e -> e.getEventApprovalStatus() != null
                            && e.getEventApprovalStatus().name().equalsIgnoreCase(status.trim()))
                    .collect(Collectors.toList());
        }
        return events.stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
                        return b.getCreatedAt().compareTo(a.getCreatedAt());
                    }
                    return b.getId().compareTo(a.getId());
                })
                .map(this::mapToEventResponse)
                .collect(Collectors.toList());
    }

    /**
     * Duyệt sự kiện: đặt trạng thái {@code APPROVED}, cấp vai trò ORGANIZER cho chủ sự kiện và gửi thông báo (khác nhau giữa duyệt lần đầu và duyệt bản cập nhật).
     * @param id id sự kiện
     * @return sự kiện sau khi duyệt
     * @throws RuntimeException khi không tìm thấy sự kiện
     */
    @Override
    public EventResponse approveEvent(String id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sự kiện với ID: " + id));

        EventApprovalStatus prevStatus = event.getEventApprovalStatus();
        event.setEventApprovalStatus(EventApprovalStatus.APPROVED);
        event.setEventStatus(EventStatus.APPROVED);
        Event saved = eventRepository.save(event);

        String recipientUserId = recipientOf(saved);

        if (!"user_default_id".equals(recipientUserId)) {
            try {
                userInternalServiceClient.addRoleToUser(recipientUserId, "ORGANIZER");
            } catch (Exception e) {
                System.err.println("Failed to grant ORGANIZER role to user: " + recipientUserId);
            }
        }

        Map<String, Object> approvalVariables = eventNotificationVariables(saved, List.of());
        if (prevStatus == EventApprovalStatus.UPDATE_PENDING) {
            publishNotification(recipientUserId, "EVENT_UPDATE_APPROVED", "Sự kiện đã được phê duyệt cập nhật!",
                    "Các thay đổi cập nhật cho sự kiện \"" + saved.getTitle() + "\" đã được phê duyệt.", approvalVariables);
        } else {
            publishNotification(recipientUserId, "EVENT_APPROVED", "Sự kiện đã được phê duyệt!",
                    "Sự kiện \"" + saved.getTitle() + "\" của bạn đã được phê duyệt chính thức.", approvalVariables);
        }

        return mapToEventResponse(saved);
    }

    /**
     * Từ chối sự kiện: đặt trạng thái {@code REJECTED}, lưu lý do và ghi chú, rồi thông báo cho chủ sự kiện kèm các mục cần sửa.
     * @param id id sự kiện
     * @param reason lý do từ chối
     * @param notes ghi chú dạng JSON (các mục đánh dấu)
     * @return sự kiện sau khi từ chối
     * @throws RuntimeException khi không tìm thấy sự kiện
     */
    @Override
    public EventResponse rejectEvent(String id, String reason, String notes) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sự kiện với ID: " + id));

        event.setEventApprovalStatus(EventApprovalStatus.REJECTED);
        event.setRejectionReason(reason);
        event.setRejectionNotes(notes);
        Event saved = eventRepository.save(event);

        String recipientUserId = recipientOf(saved);

        List<String> flagged = flaggedFieldsOf(notes);
        String where = flagged.isEmpty() ? "" : " Mục cần sửa: "
                + flagged.stream().map(f -> FLAG_LABELS.get(f)).collect(Collectors.joining(", ")) + ".";
        publishNotification(recipientUserId, "EVENT_REJECTED", "Sự kiện đã bị từ chối, xem lý do.",
                "Sự kiện \"" + saved.getTitle() + "\" của bạn bị từ chối phê duyệt. Lý do: "
                        + shorten(reason != null && !reason.isBlank() ? reason : "Cần chỉnh sửa lại thông tin.", 500) + "."
                        + where + " Nhấn vào để xem chi tiết và chỉnh sửa.",
                eventNotificationVariables(saved, flagged));

        return mapToEventResponse(saved);
    }

    /**
     * Thu hồi phê duyệt, đưa sự kiện về {@code PENDING}. Chỉ áp dụng cho sự kiện đã duyệt và chưa bán vé nào.
     * @param id id sự kiện
     * @param reason lý do thu hồi, có thể null
     * @return sự kiện sau khi thu hồi
     * @throws ResponseStatusException 400 khi sự kiện chưa được duyệt, 409 khi đã có vé bán ra, 503 khi không kiểm tra được số vé đã bán
     */
    @Override
    public EventResponse revertApproval(String id, String reason) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sự kiện với ID: " + id));

        if (event.getEventApprovalStatus() != EventApprovalStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ có thể hoàn tác sự kiện đã được phê duyệt.");
        }
        requireNoTicketsSold(id);

        event.setEventApprovalStatus(EventApprovalStatus.PENDING);
        event.setEventStatus(EventStatus.PENDING);
        event.setRejectionReason(reason != null ? reason : "Hoàn tác phê duyệt nhầm bởi Quản trị viên");
        Event saved = eventRepository.save(event);

        String recipientUserId = recipientOf(saved);
        publishNotification(recipientUserId, "EVENT_APPROVAL_REVERTED", "Thu hồi phê duyệt sự kiện",
                "Phê duyệt cho sự kiện \"" + saved.getTitle() + "\" đã được Quản trị viên thu hồi để kiểm duyệt lại. Lý do: "
                        + (reason != null && !reason.isBlank() ? reason : "Xem xét điều chỉnh lại thông tin."));

        return mapToEventResponse(saved);
    }

    /**
     * Chủ sự kiện rút yêu cầu duyệt đang chờ, đưa sự kiện về {@code DRAFT} và báo admin.
     * @param id id sự kiện
     * @param authorization header Authorization của người gọi
     * @return sự kiện sau khi rút
     * @throws ResponseStatusException 403 khi không phải chủ sự kiện, 409 khi đang ở trạng thái cập nhật chờ duyệt, 400 khi không ở trạng thái chờ duyệt
     */
    @Override
    public EventResponse cancelApprovalRequest(String id, String authorization) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sự kiện với ID: " + id));

        OrganizerEligibilityResponse eligibility = requireEligibleOrganizer(authorization);
        String uid = eligibility.userId();

        boolean isOwner = (event.getOrganizers() != null && event.getOrganizers().stream()
                .anyMatch(org -> uid != null && uid.equals(org.getUserId())))
                || staffAssignmentRepository.findByEventId(id).stream().anyMatch(a ->
                        a.getRole() == com.haloticket.event_service.enums.StaffRole.OWNER
                                && a.getStatus() == StaffAssignmentStatus.ACTIVE && uid != null && uid.equals(a.getUserId()));
        if (!isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không phải chủ sở hữu của sự kiện này.");
        }

        if (event.getEventApprovalStatus() == EventApprovalStatus.UPDATE_PENDING) {
            // các chỉnh sửa của sự kiện đã duyệt đã có hiệu lực trong lúc chờ xem xét; nếu cho hủy thì chúng được giữ lại mà không qua duyệt
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sự kiện đang mở bán với nội dung cập nhật chờ duyệt. Vui lòng chờ quản trị viên xử lý hoặc chỉnh sửa lại nội dung.");
        }
        if (event.getEventApprovalStatus() != EventApprovalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ có thể hoàn tác yêu cầu duyệt khi sự kiện đang ở trạng thái chờ duyệt.");
        }

        event.setEventApprovalStatus(EventApprovalStatus.DRAFT);
        event.setEventStatus(EventStatus.DRAFT);
        Event saved = eventRepository.save(event);

        publishNotification("ADMIN", "EVENT_APPROVAL_CANCELLED", "Hủy yêu cầu duyệt sự kiện",
                "Ban tổ chức đã thu hồi yêu cầu phê duyệt cho sự kiện \"" + saved.getTitle() + "\".");

        return mapToEventResponse(saved);
    }

    /** Thu hồi phê duyệt sẽ để người mua giữ vé của một sự kiện không còn được duyệt, nên chỉ cho thu hồi khi chưa bán vé nào. */
    private void requireNoTicketsSold(String eventId) {
        if (bookingStatsClient == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa kiểm tra được số vé đã bán, vui lòng thử lại.");
        }
        com.haloticket.event_service.client.BookingStatsResponse stats;
        try {
            stats = bookingStatsClient.getEventBookingStats(eventId);
        } catch (Exception e) {
            log.warn("Cannot read the sold tickets of event {} before reverting its approval: {}", eventId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa kiểm tra được số vé đã bán, vui lòng thử lại.");
        }
        long sold = stats == null ? 0 : stats.getTotalTicketsSold();
        if (sold > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sự kiện đã bán " + sold + " vé nên không thể hoàn tác phê duyệt. Hãy xử lý các đơn đã bán trước.");
        }
    }

    /** Các mục trong biểu mẫu từ chối mà admin có thể đánh dấu, kèm nhãn mà nhà tổ chức đọc trong thông báo. */
    private static final Map<String, String> FLAG_LABELS = Map.of(
            "posterImage", "Poster & Banner", "title", "Tên & Mô tả", "dates", "Thời gian",
            "tickets", "Hạng vé", "location", "Địa điểm", "paymentInfo", "Thanh toán");

    /** Các mục admin đã đánh dấu (ghi chú từ chối là JSON gồm flaggedFields, noteText, flaggedQuotes); tên không biết sẽ bị bỏ. */
    private List<String> flaggedFieldsOf(String notes) {
        if (notes == null || notes.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(notes);
            List<String> out = new ArrayList<>();
            for (JsonNode field : root.path("flaggedFields")) {
                String name = field.asText("");
                if (FLAG_LABELS.containsKey(name) && !out.contains(name)) out.add(name);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Dữ liệu để web mở đúng sự kiện từ thông báo: id sự kiện, đường dẫn cần tới và các mục cần sửa. */
    private static Map<String, Object> eventNotificationVariables(Event event, List<String> flaggedFields) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("eventId", event.getId());
        variables.put("link", "/organizer/events/" + event.getId() + "/manage/edit");
        if (!flaggedFields.isEmpty()) variables.put("flaggedFields", flaggedFields);
        return variables;
    }

    /** Rút gọn văn bản còn tối đa {@code max} ký tự, thêm dấu ba chấm khi bị cắt. */
    private static String shorten(String text, int max) {
        String value = text == null ? "" : text.strip();
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    /** Gửi thông báo không kèm dữ liệu bổ sung. */
    private void publishNotification(String userId, String type, String title, String message) {
        publishNotification(userId, type, title, message, null);
    }

    /**
     * Gửi thông báo cho người dùng (hoặc nhóm như {@code ADMIN}) qua Kafka (topic {@code haloticket.direct.notification.v1}) và qua notification-service nếu có. Lỗi gửi bị bỏ qua để không ảnh hưởng nghiệp vụ chính.
     * @param userId người nhận, rỗng thì không gửi
     * @param type loại thông báo
     * @param title tiêu đề
     * @param message nội dung
     * @param variables dữ liệu kèm theo (dạng JSON), có thể null
     */
    private void publishNotification(String userId, String type, String title, String message, Map<String, Object> variables) {
        if (userId == null || userId.isBlank()) return;

        Map<String, String> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("recipientUserId", userId);
        payload.put("type", type);
        payload.put("title", title);
        payload.put("message", message);
        payload.put("timestamp", LocalDateTime.now().toString());
        if (variables != null && !variables.isEmpty()) {
            try {
                payload.put("variables", objectMapper.writeValueAsString(variables));
            } catch (Exception e) {
                // thông báo vẫn được gửi, chỉ thiếu dữ liệu kèm theo
            }
        }

        if (kafkaTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(payload);
                kafkaTemplate.send("haloticket.direct.notification.v1", userId, json);
            } catch (Exception e) {
                // bỏ qua lỗi, thông báo không được làm hỏng nghiệp vụ chính
            }
        }

        if (notificationServiceClient != null) {
            try {
                notificationServiceClient.publishNotification(new HashMap<>(payload));
            } catch (Exception e) {
                // bỏ qua lỗi, thông báo không được làm hỏng nghiệp vụ chính
            }
        }
    }

    /** Chuyển danh mục sang response; trạng thái trống được coi là {@code ACTIVE}. */
    private EventCategoryResponse mapToCategoryResponse(EventCategory category) {
        if (category == null)
            return null;
        return EventCategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .status(category.getStatus() != null ? category.getStatus().name() : "ACTIVE")
                .slug(category.getSlug())
                .imageUrl(category.getImageUrl())
                .icon(category.getIcon())
                .sortOrder(category.getSortOrder())
                .build();
    }
}
