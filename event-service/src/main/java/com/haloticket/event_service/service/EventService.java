package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerDto;

import java.util.List;

/** Nghiệp vụ chính về sự kiện: tạo và cập nhật, truy vấn, duyệt của admin, danh mục, từ khóa tìm kiếm và đồng bộ vé sang ticket-service. */
public interface EventService {
    /** Tạo mới hoặc cập nhật (khi request có {@code eventId}) sự kiện, đồng bộ vé sang ticket-service và gửi thông báo duyệt cho admin. */
    EventResponse createEvent(EventCreateRequest request, String authorization);
    /** Lấy toàn bộ sự kiện. */
    List<EventResponse> getAllEvents();
    /** Lấy các sự kiện mà người gọi có phân công ACTIVE; trả danh sách rỗng khi không xác định được người dùng. */
    List<EventResponse> getMyEvents(String authorization);
    /** Lấy chi tiết một sự kiện theo id. */
    EventResponse getEventById(String id);
    /** Lấy các danh mục sự kiện theo thứ tự hiển thị. */
    List<EventCategoryResponse> getAllCategories();
    /** Lấy các từ khóa được tìm nhiều nhất, tối đa {@code limit} từ khóa. */
    List<String> getTopSearchedKeywords(int limit);
    /** Ghi nhận một lượt tìm kiếm: tăng số lần tìm của từ khóa (chuẩn hóa về chữ thường). */
    void logSearchQuery(String query);
    /** Lấy hồ sơ nhà tổ chức của người gọi; trả null khi chưa có hoặc chưa đủ điều kiện. */
    OrganizerDto getOrganizerProfile(String authorization);
    /** Lấy các sự kiện theo trạng thái duyệt (hoặc {@code ALL}), mới nhất trước, cho màn hình duyệt của admin. */
    List<EventResponse> getApprovalEvents(String status);
    /** Admin duyệt sự kiện, cấp vai trò ORGANIZER cho chủ sự kiện và gửi thông báo. */
    EventResponse approveEvent(String id);
    /** Admin từ chối sự kiện kèm lý do và ghi chú, rồi thông báo cho chủ sự kiện. */
    EventResponse rejectEvent(String id, String reason, String notes);
    /** Admin thu hồi phê duyệt, đưa sự kiện về chờ duyệt; chỉ được khi chưa bán vé nào. */
    EventResponse revertApproval(String id, String reason);
    /** Chủ sự kiện rút lại yêu cầu duyệt đang chờ, đưa sự kiện về nháp. */
    EventResponse cancelApprovalRequest(String id, String authorization);
    /** Gửi lại bản chụp vé đã lưu sang ticket-service. Ném {@code TicketSyncFailedException} hoặc {@code TicketSyncRejectedException} khi đồng bộ thất bại. */
    EventResponse resyncTickets(String eventId);
    /** Cập nhật sự kiện có sẵn; dùng chung luồng lưu với {@code createEvent}. */
    EventResponse updateEvent(String id, EventCreateRequest request, String authorization);
}
