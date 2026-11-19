package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.response.EventSearchPage;
import com.haloticket.event_service.search.EventSearchCriteria;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerDto;
import com.haloticket.event_service.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.Authentication;

import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.enums.EventStatus;
import org.springframework.web.server.ResponseStatusException;
import com.haloticket.event_service.exception.TicketSyncFailedException;
import com.haloticket.event_service.exception.TicketSyncRejectedException;
import com.haloticket.event_service.service.EventAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** API chính của sự kiện: tạo và sửa sự kiện, hồ sơ nhà tổ chức, danh sách, duyệt sự kiện và nhật ký tìm kiếm. */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final EventAccessService eventAccessService;

    /**
     * Endpoint thử nghiệm: trả về chuỗi JSON của danh sách câu hỏi tùy chỉnh trong yêu cầu; lỗi được trả dưới dạng chuỗi {@code Error: ...} với mã 200.
     * @param request yêu cầu tạo sự kiện cần kiểm tra
     * @return chuỗi JSON hoặc thông báo lỗi
     */
    @PostMapping("/test-questions")
    public ResponseEntity<String> testQuestions(@RequestBody EventCreateRequest request) {
        try {
            return ResponseEntity.ok(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.getCustomQuestions()));
        } catch (Exception e) {
            return ResponseEntity.ok("Error: " + e.getMessage());
        }
    }

    /**
     * Tạo sự kiện mới (hoặc hoàn thiện bản nháp, tùy dữ liệu gửi lên).
     * @param authorization header {@code Authorization} của người tổ chức
     * @param request dữ liệu sự kiện
     * @return sự kiện đã tạo với mã 201
     */
    @PostMapping
    public ResponseEntity<EventResponse> createEvent(
            @RequestHeader("Authorization") String authorization,
            @RequestBody EventCreateRequest request
    ) {
        return new ResponseEntity<>(eventService.createEvent(request, authorization), HttpStatus.CREATED);
    }

    /**
     * Cập nhật sự kiện đã có.
     * @param id mã sự kiện
     * @param authorization header {@code Authorization} của người gọi
     * @param request dữ liệu sự kiện mới
     * @return sự kiện sau khi cập nhật
     */
    @PutMapping("/{id}")
    public ResponseEntity<EventResponse> updateEvent(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorization,
            @RequestBody EventCreateRequest request
    ) {
        return ResponseEntity.ok(eventService.updateEvent(id, request, authorization));
    }

    /**
     * Gửi lại cho ticket-service bản chụp vé đã lưu. Cần cùng quyền với sửa sự kiện ({@code EVENT_EDIT}; {@code ROLE_ADMIN} được qua).
     * @param id mã sự kiện
     * @param authorization header {@code Authorization} của người gọi
     * @return sự kiện sau khi đồng bộ lại
     */
    @PostMapping("/{id}/tickets/resync")
    public ResponseEntity<EventResponse> resyncTickets(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorization
    ) {
        eventAccessService.require(id, Permission.EVENT_EDIT, authorization);
        return ResponseEntity.ok(eventService.resyncTickets(id));
    }

    /**
     * Chuyển việc ticket-service từ chối đồng bộ thành phản hồi 422 gồm mã lỗi, mã sự kiện và chi tiết loại vé.
     * @param exception ngoại lệ từ chối đồng bộ
     * @return phản hồi 422 dạng JSON
     */
    @ExceptionHandler(TicketSyncRejectedException.class)
    public ResponseEntity<Map<String, Object>> ticketSyncRejected(TicketSyncRejectedException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", exception.getCode());
        body.put("eventId", exception.getEventId());
        body.put("message", exception.getCode());
        body.putAll(exception.getDetails()); // gồm ticketTypeId, name, sold, held, minAllowed
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * Chuyển lỗi đồng bộ vé (sự kiện đã lưu nhưng vé chưa đồng bộ được) thành phản hồi 502 kèm gợi ý gọi lại bằng resync.
     * @param exception ngoại lệ lỗi đồng bộ
     * @return phản hồi 502 dạng JSON
     */
    @ExceptionHandler(TicketSyncFailedException.class)
    public ResponseEntity<Map<String, Object>> ticketSyncFailed(TicketSyncFailedException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "TICKETS_SYNC_FAILED");
        body.put("eventId", exception.getEventId());
        body.put("message", "Event saved but its ticket types could not be synchronized. Try again with resync.");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * Lấy hồ sơ nhà tổ chức của người đang đăng nhập.
     * @param authorization header {@code Authorization} của người gọi
     * @param jwt JWT của người gọi
     * @return hồ sơ nhà tổ chức, hoặc 204 nếu chưa có
     */
    @GetMapping("/organizer-profile")
    public ResponseEntity<OrganizerDto> getOrganizerProfile(
            @RequestHeader("Authorization") String authorization,
            @AuthenticationPrincipal Jwt jwt
    ) {
        OrganizerDto dto = eventService.getOrganizerProfile(authorization);
        if (dto == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * Lấy danh sách sự kiện công khai (đã duyệt, không riêng tư). Người tổ chức xem sự kiện của mình (mọi trạng thái) ở {@code /my-events}.
     * @return danh sách sự kiện công khai
     */
    @GetMapping
    public ResponseEntity<List<EventResponse>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    /**
     * Tìm kiếm sự kiện công khai ở phía máy chủ, có lọc, sắp xếp và phân trang. Chỉ trả sự kiện đã duyệt và không riêng tư.
     *
     * @param q từ khóa (không phân biệt dấu); mọi từ đều phải xuất hiện
     * @param city thành phố: {@code Hồ Chí Minh}, {@code Hà Nội}, {@code Đà Lạt}, {@code other} hoặc chữ tự do
     * @param categoryId id danh mục
     * @param category tên danh mục
     * @param from ngày bắt đầu khoảng thời gian (yyyy-MM-dd, tính cả ngày này)
     * @param to ngày cuối khoảng thời gian (yyyy-MM-dd, tính cả ngày này)
     * @param free {@code true} để chỉ lấy sự kiện miễn phí
     * @param sort {@code relevance}, {@code soonest}, {@code priceAsc} hoặc {@code priceDesc}
     * @param includeEnded {@code true} để lấy cả sự kiện đã hết hạn
     * @param page số trang từ 0
     * @param size số sự kiện mỗi trang (tối đa 50)
     * @return một trang kết quả
     */
    @GetMapping("/search")
    public ResponseEntity<EventSearchPage> searchEvents(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean free,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean includeEnded,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(eventService.searchEvents(new EventSearchCriteria(
                q, city, categoryId, category, from, to, free, EventSearchCriteria.Sort.parse(sort), includeEnded, page, size)));
    }

    /**
     * Endpoint thử nghiệm xác thực: cho biết JWT có hợp lệ hay không.
     * @param jwt JWT của người gọi
     * @return 401 nếu không có JWT, ngược lại chuỗi xác nhận kèm chủ thể
     */
    @GetMapping("/test-auth")
    public ResponseEntity<String> testAuth(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) return ResponseEntity.status(401).body("JWT is NULL");
        return ResponseEntity.ok("JWT is VALID: " + jwt.getSubject());
    }

    /**
     * Lấy các sự kiện của người đang đăng nhập.
     * @param authorization header {@code Authorization} của người gọi
     * @param jwt JWT của người gọi
     * @param auth thông tin xác thực hiện tại, chỉ dùng để ghi log gỡ lỗi
     * @return danh sách sự kiện, hoặc 401 nếu không có JWT
     */
    @GetMapping("/my-events")
    public ResponseEntity<List<EventResponse>> getMyEvents(@RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        System.out.println("DEBUG: getMyEvents called. auth=" + auth);
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<EventResponse> res = eventService.getMyEvents(authorization);
        System.out.println("DEBUG: getMyEvents returned " + (res != null ? res.size() : "null") + " events for user " + jwt.getSubject());
        return ResponseEntity.ok(res);
    }

    /**
     * Lấy danh sách danh mục sự kiện (công khai).
     * @return danh sách danh mục
     */
    @GetMapping("/categories")
    public ResponseEntity<List<EventCategoryResponse>> getAllCategories() {
        return ResponseEntity.ok(eventService.getAllCategories());
    }

    /**
     * Lấy các sự kiện cần duyệt, có thể lọc theo trạng thái duyệt.
     * @param status trạng thái duyệt cần lọc; bỏ trống thì lấy theo mặc định của service
     * @return danh sách sự kiện
     */
    @GetMapping("/approvals")
    public ResponseEntity<List<EventResponse>> getApprovalEvents(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(eventService.getApprovalEvents(status));
    }

    /**
     * Phê duyệt sự kiện (chỉ quản trị viên).
     * @param id mã sự kiện
     * @return sự kiện sau khi duyệt
     */
    @PatchMapping("/{id}/approve")
    public ResponseEntity<EventResponse> approveEvent(@PathVariable String id) {
        return ResponseEntity.ok(eventService.approveEvent(id));
    }

    /**
     * Hoàn tác việc phê duyệt sự kiện (chỉ quản trị viên).
     * @param id mã sự kiện
     * @param body có thể chứa {@code reason}; không gửi thân thì dùng lý do mặc định
     * @return sự kiện sau khi hoàn tác
     */
    @PatchMapping("/{id}/revert-approval")
    public ResponseEntity<EventResponse> revertApproval(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : "Hoàn tác phê duyệt nhầm bởi Quản trị viên";
        return ResponseEntity.ok(eventService.revertApproval(id, reason));
    }

    /**
     * Nhà tổ chức rút lại yêu cầu duyệt sự kiện.
     * @param id mã sự kiện
     * @param authorization header {@code Authorization} của người gọi
     * @return sự kiện sau khi hủy yêu cầu
     */
    @PatchMapping("/{id}/cancel-approval-request")
    public ResponseEntity<EventResponse> cancelApprovalRequest(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventService.cancelApprovalRequest(id, authorization));
    }

    /**
     * Từ chối sự kiện (chỉ quản trị viên).
     * @param id mã sự kiện
     * @param body có thể chứa {@code reason} và {@code notes}; không gửi thân thì dùng lý do mặc định
     * @return sự kiện sau khi bị từ chối
     */
    @PatchMapping("/{id}/reject")
    public ResponseEntity<EventResponse> rejectEvent(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : "Không đạt tiêu chuẩn kiểm duyệt";
        String notes = body != null ? body.get("notes") : "";
        return ResponseEntity.ok(eventService.rejectEvent(id, reason, notes));
    }

    /**
     * Lấy các từ khóa được tìm nhiều nhất.
     * @param limit số từ khóa tối đa, mặc định 3
     * @return danh sách từ khóa
     */
    @GetMapping("/trending-searches")
    public ResponseEntity<List<String>> getTrendingSearches(@RequestParam(defaultValue = "3") int limit) {
        return ResponseEntity.ok(eventService.getTopSearchedKeywords(limit));
    }

    /**
     * Ghi lại một từ khóa tìm kiếm để thống kê (chấp nhận cả POST và GET).
     * @param query từ khóa người dùng tìm
     * @return 200 không có thân
     */
    @RequestMapping(value = "/search-log", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<Void> logSearchQuery(@RequestParam String query) {
        eventService.logSearchQuery(query);
        return ResponseEntity.ok().build();
    }

    /**
     * Lấy chi tiết sự kiện. Sự kiện đã duyệt là công khai; sự kiện nháp, chờ duyệt hoặc bị từ chối chỉ người trong ban tổ chức
     * của sự kiện đó hoặc admin nền tảng mới xem được, người khác nhận 404 như thể sự kiện không tồn tại.
     * @param id mã sự kiện
     * @param authorization header Authorization nếu người gọi đã đăng nhập
     * @return sự kiện, hoặc 404 nếu không tồn tại hoặc không được phép xem
     */
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEventById(@PathVariable String id,
                                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        EventResponse response = eventService.getEventById(id);
        if (response == null || (!isPublished(response) && !canViewUnpublished(id, authorization))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    /** Sự kiện đã qua kiểm duyệt (đã duyệt, đang bán, đã kết thúc hoặc bị hủy) thì ai cũng xem được; nháp, chờ duyệt và bị từ chối thì không. */
    private static boolean isPublished(EventResponse event) {
        if (event.getEventStatus() == EventStatus.DRAFT || event.getEventStatus() == EventStatus.PENDING) return false;
        String approval = event.getEventApprovalStatus();
        return !("DRAFT".equals(approval) || "PENDING".equals(approval) || "REJECTED".equals(approval));
    }

    /** Người gọi có vai trò đang hoạt động trong sự kiện (chủ, quản trị, quản lý, nhân sự) hoặc là admin nền tảng; chưa đăng nhập thì không. */
    private boolean canViewUnpublished(String eventId, String authorization) {
        if (authorization == null || authorization.isBlank()) return false;
        try {
            EventAccess access = eventAccessService.forCaller(eventId, authorization);
            return access.platformAdmin() || access.role() != null;
        } catch (ResponseStatusException denied) {
            return false;
        }
    }
}
