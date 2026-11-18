package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.authz.EventAccessClient;
import com.haloticket.ticket_service.authz.EventAccessView;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.CheckinPageDTO;
import com.haloticket.ticket_service.dto.response.CheckinSummaryDTO;
import com.haloticket.ticket_service.dto.response.CheckinTicketLookupDTO;
import com.haloticket.ticket_service.dto.response.TicketCheckinDTO;
import com.haloticket.ticket_service.service.CheckinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** API check-in tại cửa soát vé: quét vé, xem danh sách đã vào, thống kê và tìm vé. Quyền được kiểm tra qua event-service cho từng sự kiện. */
@Slf4j
@RestController
@RequestMapping("/api/tickets/checkin")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;
    private final EventAccessClient eventAccess;

    /**
     * Xử lý một lần quét vé. Yêu cầu quyền {@code CHECKIN_SCAN}; mã nhân viên trong yêu cầu luôn bị thay bằng mã đã xác minh
     * từ quyền của người gọi để không thể giả mạo.
     *
     * @param request thông tin vé quét và sự kiện
     * @param authorization header {@code Authorization} của người gọi
     * @return kết quả check-in
     */
    @PostMapping
    public ResponseEntity<TicketCheckinDTO> processCheckin(@RequestBody CheckinRequest request,
                                                           @RequestHeader(value = "Authorization", required = false) String authorization) {
        EventAccessView access = eventAccess.require(request.getEventId(), "CHECKIN_SCAN", authorization);
        String verified = staffIdOf(access);
        if (request.getStaffAssignmentId() != null && !request.getStaffAssignmentId().equals(verified)) {
            log.info("[CHECKIN_STAFF_ID_REPLACED] event={} client sent a different staff id than the verified one", request.getEventId());
        }
        request.setStaffAssignmentId(verified);
        TicketCheckinDTO result = checkinService.processCheckin(request);
        return ResponseEntity.ok(result);
    }

    /** Liệt kê các lượt check-in của sự kiện (có thể lọc theo suất diễn). Yêu cầu quyền {@code CHECKIN_VIEW}. */
    @GetMapping("/events/{eventId}")
    public ResponseEntity<List<TicketCheckinDTO>> getCheckinsByEvent(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "showtimeId", required = false) String showtimeId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        List<TicketCheckinDTO> results = checkinService.getCheckinsByEvent(eventId, showtimeId);
        return ResponseEntity.ok(results);
    }

    /** Danh sách người đã vào theo từng trang (trang đếm từ 0), mới nhất trước; {@code showtimeId} giới hạn trong một suất diễn. */
    @GetMapping("/events/{eventId}/page")
    public ResponseEntity<CheckinPageDTO> getCheckinPage(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "showtimeId", required = false) String showtimeId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        return ResponseEntity.ok(checkinService.getCheckinPage(eventId, showtimeId, page, size));
    }

    /** Số đã vào trên tổng số vé cho cửa soát, theo từng suất diễn và từng loại vé. Nhân viên cửa được đọc (cùng quyền với danh sách). */
    @GetMapping("/events/{eventId}/summary")
    public ResponseEntity<CheckinSummaryDTO> getSummary(
            @PathVariable("eventId") String eventId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        return ResponseEntity.ok(checkinService.getSummary(eventId));
    }

    /** Tìm vé theo một đoạn mã vé, mã đơn, vị trí ghế hoặc tên loại vé (ít nhất hai ký tự). */
    @GetMapping("/events/{eventId}/search")
    public ResponseEntity<List<CheckinTicketLookupDTO>> search(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "q", defaultValue = "") String q,
            @RequestParam(value = "showtimeId", required = false) String showtimeId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        EventAccessView access = eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        return ResponseEntity.ok(checkinService.search(eventId, q, showtimeId, limit, access.has("CHECKIN_SCAN")));
    }

    /** Tạo mã nhân viên check-in từ quyền: dùng {@code assignmentId} nếu có, ngược lại ghép tiền tố vai trò với tám ký tự đầu của mã người dùng. */
    private static String staffIdOf(EventAccessView access) {
        if (access.assignmentId() != null && !access.assignmentId().isBlank()) return access.assignmentId();
        String who = access.userId() != null ? access.userId() : "unknown";
        String prefix = access.platformAdmin() ? "PLATFORM_ADMIN:" : "EVENT_MEMBER:";
        return prefix + (who.length() > 8 ? who.substring(0, 8) : who);
    }
}
