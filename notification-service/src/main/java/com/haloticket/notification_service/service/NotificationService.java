package com.haloticket.notification_service.service;

import com.haloticket.notification_service.enums.InboxChannel;

import com.haloticket.notification_service.dto.request.NotificationCreateRequest;
import com.haloticket.notification_service.dto.request.NotificationTemplateCreateRequest;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.dto.response.NotificationTemplateResponse;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import com.haloticket.notification_service.event.NotificationEvent;

/** Nghiệp vụ thông báo: mẫu, thông báo của người dùng, xử lý sự kiện Kafka và phát thông báo trực tiếp. */
public interface NotificationService {
    /**
     * Tạo mẫu thông báo; trạng thái mặc định {@code ACTIVE}.
     *
     * @param request nội dung mẫu
     * @return mẫu đã lưu
     */
    NotificationTemplateResponse createTemplate(NotificationTemplateCreateRequest request);

    /**
     * Lấy toàn bộ mẫu.
     *
     * @return danh sách mẫu
     */
    List<NotificationTemplateResponse> getTemplates();

    /**
     * Tạo thông báo từ mẫu đang hoạt động.
     *
     * @param request mã mẫu, người nhận, biến, kênh
     * @return thông báo đã lưu
     * @throws NotificationException khi không có mẫu (404) hoặc mẫu không hoạt động (409)
     */
    NotificationResponse createNotification(NotificationCreateRequest request);

    /**
     * Lấy thông báo của người dùng, mới nhất trước.
     *
     * @param userId mã người dùng
     * @return danh sách thông báo
     */
    List<NotificationResponse> getUserNotifications(Collection<String> aliases, InboxChannel channel);

    default List<NotificationResponse> getUserNotifications(Collection<String> aliases) {
        return getUserNotifications(aliases, InboxChannel.PUBLIC);
    }

    /**
     * Đánh dấu đã gửi và ghi thời điểm gửi.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     * @throws NotificationException khi không tìm thấy (404)
     */
    NotificationResponse markAsSent(String id);

    /**
     * Đánh dấu gửi thất bại.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     * @throws NotificationException khi không tìm thấy (404)
     */
    NotificationResponse markAsFailed(String id);

    /**
     * Đánh dấu đã đọc.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     * @throws NotificationException khi không tìm thấy (404)
     */
    NotificationResponse markAsRead(String id, Collection<String> aliases);

    /** Mark one owned notification as unread. */
    NotificationResponse markAsUnread(String id, Collection<String> aliases);

    /**
     * Đánh dấu mọi thông báo của người dùng là đã đọc.
     *
     * @param userId mã người dùng
     * @return danh sách sau cập nhật
     */
    List<NotificationResponse> markAllAsRead(Collection<String> aliases, Instant startedAt, InboxChannel channel);

    void delete(String id, Collection<String> aliases);

    NotificationResponse publishDirectEvent(String eventId, String userId, String type, String title, String message, String variablesJson);

    /**
     * Lưu thông báo từ sự kiện Kafka, idempotent theo {@code eventId}, rồi đẩy qua STOMP.
     *
     * @param event sự kiện thông báo
     * @return thông báo đã có hoặc vừa tạo
     */
    NotificationResponse processEvent(NotificationEvent event);

    /**
     * Như {@link #processEvent(NotificationEvent)} nhưng cho phép gắn loại, tiêu đề, nội dung hiển thị trong chuông. Bản mặc định bỏ qua các tham số này.
     *
     * @param event sự kiện thông báo
     * @param type loại hiển thị
     * @param title tiêu đề
     * @param message nội dung
     * @return thông báo đã có hoặc vừa tạo
     */
    default NotificationResponse processEvent(NotificationEvent event, String type, String title, String message) {
        return processEvent(event);
    }

    /**
     * Lưu và phát ngay một thông báo cho người dùng, không qua mẫu.
     *
     * @param userId mã người nhận
     * @param type loại thông báo
     * @param title tiêu đề
     * @param message nội dung
     * @param variablesJson dữ liệu bổ sung dạng JSON object, có thể null
     * @return thông báo đã lưu
     */
    NotificationResponse publishDirectNotification(String userId, String type, String title, String message, String variablesJson);

    /**
     * Như bản đầy đủ nhưng không có dữ liệu bổ sung.
     *
     * @param userId mã người nhận
     * @param type loại thông báo
     * @param title tiêu đề
     * @param message nội dung
     * @return thông báo đã lưu
     */
    default NotificationResponse publishDirectNotification(String userId, String type, String title, String message) {
        return publishDirectNotification(userId, type, title, message, null);
    }
}

