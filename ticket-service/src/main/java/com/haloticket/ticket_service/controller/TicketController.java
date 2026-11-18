package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.authz.EventAccessClient;
import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.exception.QuantityBelowSoldAndHeldException;
import com.haloticket.ticket_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** API quản lý loại vé và sơ đồ ghế của sự kiện. Thao tác ghi cần quyền {@code EVENT_EDIT} trên sự kiện hoặc secret nội bộ của dịch vụ. */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final TicketService ticketService;
    private final EventAccessClient eventAccess;

    /** Chốt chặn ghi theo sự kiện: dịch vụ mang secret nội bộ (đồng bộ từ event-service) được qua, còn lại cần quyền {@code EVENT_EDIT}. */
    private void requireEventEdit(String eventId, String authorization, String internalToken) {
        if (eventAccess.isInternal(internalToken)) return;
        eventAccess.require(eventId, "EVENT_EDIT", authorization);
    }

    /** Tạo một loại vé cho sự kiện; trả 201 với loại vé vừa tạo. */
    @PostMapping("/types")
    public ResponseEntity<TicketTypeResponse> createTicketType(@RequestBody TicketTypeCreateRequest request,
                                                               @RequestHeader(value = "Authorization", required = false) String authorization,
                                                               @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalToken) {
        requireEventEdit(request.getEventId(), authorization, internalToken);
        TicketTypeResponse response = ticketService.createTicketType(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    /**
     * Đồng bộ toàn bộ loại vé của sự kiện (có thể giới hạn theo suất diễn) từ danh sách gửi lên.
     * Nếu một loại vé bị giảm số lượng xuống dưới số đã bán và đang giữ thì trả 422 kèm {@code QUANTITY_BELOW_SOLD_AND_HELD}.
     */
    @PostMapping("/types/sync/{eventId}")
    public ResponseEntity<?> syncTicketTypesForEvent(@PathVariable("eventId") String eventId,
                                                                             @RequestParam(value = "showtimeId", required = false) String showtimeId,
                                                                             @RequestBody List<TicketTypeCreateRequest> requests,
                                                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                             @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalToken) {
        requireEventEdit(eventId, authorization, internalToken);
        try {
            List<TicketTypeResponse> response = ticketService.syncTicketTypesForEvent(eventId, showtimeId, requests);
            return ResponseEntity.ok(response);
        } catch (QuantityBelowSoldAndHeldException below) {
            // transaction của service đã rollback: không có thay đổi nào của lần đồng bộ này được áp dụng
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "QUANTITY_BELOW_SOLD_AND_HELD");
            body.put("ticketTypeId", below.getTicketTypeId());
            body.put("name", below.getTicketTypeName());
            body.put("sold", below.getSold());
            body.put("held", below.getHeld());
            body.put("minAllowed", below.getMinAllowed());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
        }
    }

    /** Liệt kê các loại vé công khai của sự kiện. */
    @GetMapping("/types/event/{eventId}")
    public ResponseEntity<List<TicketTypeResponse>> getTicketTypesByEventId(@PathVariable("eventId") String eventId) {
        List<TicketTypeResponse> response = ticketService.getTicketTypesByEventId(eventId);
        return ResponseEntity.ok(response);
    }

    /** Liệt kê mọi loại vé của sự kiện (kể cả loại không công khai); cần quyền {@code ORDERS_VIEW} hoặc secret nội bộ. */
    @GetMapping("/types/event/{eventId}/all")
    public ResponseEntity<List<TicketTypeResponse>> getAllTicketTypesByEventId(@PathVariable("eventId") String eventId,
                                                                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                                   @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalToken) {
        if (!eventAccess.isInternal(internalToken)) eventAccess.require(eventId, "ORDERS_VIEW", authorization);
        List<TicketTypeResponse> response = ticketService.getAllTicketTypesByEventId(eventId);
        return ResponseEntity.ok(response);
    }

    /** Lấy một loại vé theo mã; trả 404 nếu không tồn tại. */
    @GetMapping("/types/{id}")
    public ResponseEntity<TicketTypeResponse> getTicketTypeById(@PathVariable("id") String id) {
        TicketTypeResponse response = ticketService.getTicketTypeById(id);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    /** Liệt kê các loại vé của một suất diễn. */
    @GetMapping("/types/showtime/{showtimeId}")
    public ResponseEntity<List<TicketTypeResponse>> getTicketTypesByShowtimeId(@PathVariable("showtimeId") String showtimeId) {
        List<TicketTypeResponse> response = ticketService.getTicketTypesByShowtimeId(showtimeId);
        return ResponseEntity.ok(response);
    }

    /** Lấy sơ đồ ghế của sự kiện; chưa có thì trả bản nháp rỗng ({@code DRAFT}, không có khu vực). */
    @GetMapping("/seat-layouts/event/{eventId}")
    public ResponseEntity<SeatLayoutResponse> getSeatLayoutByEventId(@PathVariable("eventId") String eventId) {
        SeatLayoutResponse response = ticketService.getSeatLayoutByEventId(eventId);
        if (response == null) {
            response = SeatLayoutResponse.builder()
                    .eventId(eventId)
                    .status("DRAFT")
                    .zones(List.of())
                    .build();
        }
        return ResponseEntity.ok(response);
    }

    /** Lưu bản nháp sơ đồ ghế của sự kiện; cần quyền {@code EVENT_EDIT}. */
    @PutMapping("/seat-layouts/event/{eventId}/draft")
    public ResponseEntity<SeatLayoutResponse> saveSeatLayoutDraft(@PathVariable("eventId") String eventId,
                                                                    @RequestBody SeatLayoutRequest request,
                                                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireEventEdit(eventId, authorization, null);
        SeatLayoutResponse response = ticketService.saveSeatLayoutDraft(eventId, request);
        return ResponseEntity.ok(response);
    }

    /** Xuất bản sơ đồ ghế của sự kiện; cần quyền {@code EVENT_EDIT}. */
    @PostMapping("/seat-layouts/event/{eventId}/publish")
    public ResponseEntity<SeatLayoutResponse> publishSeatLayout(@PathVariable("eventId") String eventId,
                                                                  @RequestBody SeatLayoutRequest request,
                                                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireEventEdit(eventId, authorization, null);
        SeatLayoutResponse response = ticketService.publishSeatLayout(eventId, request);
        return ResponseEntity.ok(response);
    }

    /** Lấy trạng thái từng ghế của sự kiện (và suất diễn nếu có) dưới dạng map {@code mã ghế -> trạng thái}. */
    @GetMapping("/seat-status")
    public ResponseEntity<Map<String, String>> getSeatStatus(@RequestParam("eventId") String eventId, @RequestParam(value = "showtimeId", required = false) String showtimeId) {
        return ResponseEntity.ok(ticketService.getSeatStatusMap(eventId, showtimeId));
    }
}