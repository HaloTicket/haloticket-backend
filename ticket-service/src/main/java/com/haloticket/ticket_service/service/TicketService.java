package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.dto.response.OwnedTicketResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/** Nghiệp vụ loại vé, sơ đồ ghế và vé của người dùng trong ticket-service. */
public interface TicketService {
    /** Tạo một loại vé mới cho sự kiện. */
    TicketTypeResponse createTicketType(TicketTypeCreateRequest request);
    /** Đồng bộ danh sách loại vé của sự kiện (và suất diễn nếu có) theo dữ liệu ban tổ chức gửi lên. */
    List<TicketTypeResponse> syncTicketTypesForEvent(String eventId, String showtimeId, List<TicketTypeCreateRequest> requests);
    /** Lấy các loại vé đang mở bán của sự kiện. */
    List<TicketTypeResponse> getTicketTypesByEventId(String eventId);
    /** Lấy mọi loại vé của sự kiện, kể cả loại không mở bán. */
    List<TicketTypeResponse> getAllTicketTypesByEventId(String eventId);
    /** Lấy một loại vé theo mã. */
    TicketTypeResponse getTicketTypeById(String id);
    /** Lấy các loại vé của một suất diễn. */
    List<TicketTypeResponse> getTicketTypesByShowtimeId(String showtimeId);

    /** Lưu bản nháp sơ đồ ghế của sự kiện. */
    SeatLayoutResponse saveSeatLayoutDraft(String eventId, SeatLayoutRequest request);
    /** Xuất bản sơ đồ ghế của sự kiện để bán vé theo ghế. */
    SeatLayoutResponse publishSeatLayout(String eventId, SeatLayoutRequest request);
    /** Lấy sơ đồ ghế của sự kiện. */
    SeatLayoutResponse getSeatLayoutByEventId(String eventId);
    /** Lấy bản đồ trạng thái ghế (mã ghế sang trạng thái) của sự kiện, theo suất diễn nếu có. */
    Map<String, String> getSeatStatusMap(String eventId, String showtimeId);
    
    /** Lấy danh sách vé của người dùng, phân trang. */
    Page<OwnedTicketResponse> getMyTickets(String userId, Pageable pageable);
    /** Lấy chi tiết một vé của người dùng. */
    OwnedTicketResponse getMyTicket(String ticketId, String userId);
}