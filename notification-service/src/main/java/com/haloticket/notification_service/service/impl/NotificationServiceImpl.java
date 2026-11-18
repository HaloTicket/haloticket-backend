package com.haloticket.notification_service.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.dto.request.NotificationCreateRequest;
import com.haloticket.notification_service.dto.request.NotificationTemplateCreateRequest;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.dto.response.NotificationTemplateResponse;
import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.repository.NotificationRepository;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.service.NotificationService;
import com.haloticket.notification_service.event.NotificationEvent;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;

/** Cài đặt nghiệp vụ thông báo: lưu qua JPA và đẩy thông báo thời gian thực qua STOMP khi có {@code SimpMessagingTemplate}. */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository notificationTemplateRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Tạo mẫu mới, trạng thái mặc định {@code ACTIVE} khi request không nêu.
     *
     * @param request nội dung mẫu
     * @return mẫu đã lưu
     */
    @Override
    public NotificationTemplateResponse createTemplate(NotificationTemplateCreateRequest request) {
        NotificationTemplate template = NotificationTemplate.builder()
                .type(request.getType())
                .channel(request.getChannel())
                .subjectTemplate(request.getSubjectTemplate())
                .bodyTemplate(request.getBodyTemplate())
                .status(request.getStatus() == null ? NotificationTemplateStatus.ACTIVE : request.getStatus())
                .build();

        return toTemplateResponse(notificationTemplateRepository.save(template));
    }

    /**
     * Lấy toàn bộ mẫu thông báo.
     *
     * @return danh sách mẫu
     */
    @Override
    public List<NotificationTemplateResponse> getTemplates() {
        return notificationTemplateRepository.findAll().stream()
                .map(this::toTemplateResponse)
                .toList();
    }

    /**
     * Tạo thông báo {@code PENDING} từ mẫu; kênh lấy từ request, thiếu thì dùng kênh của mẫu.
     *
     * @param request mã mẫu, người nhận, biến, kênh
     * @return thông báo đã lưu
     * @throws NotificationException {@code TEMPLATE_NOT_FOUND} (404) hoặc {@code TEMPLATE_INACTIVE} (409)
     */
    @Override
    public NotificationResponse createNotification(NotificationCreateRequest request) {
        NotificationTemplate template = notificationTemplateRepository.findById(request.getTemplateId())
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.TEMPLATE_NOT_FOUND,
                        "Notification template not found", 404));
        if (template.getStatus() != NotificationTemplateStatus.ACTIVE) {
            throw new NotificationException(NotificationErrorCode.TEMPLATE_INACTIVE,
                    "Notification template must be active", 409);
        }

        Notification notification = Notification.builder()
                .template(template)
                .userId(request.getUserId())
                .variablesJson(toVariablesJson(request.getVariables()))
                .channel(request.getChannel() == null ? template.getChannel() : request.getChannel())
                .status(NotificationStatus.PENDING)
                .build();

        return toNotificationResponse(notificationRepository.save(notification));
    }

    /**
     * Lấy thông báo của người dùng, mới nhất trước.
     *
     * @param userId mã người dùng
     * @return danh sách thông báo
     */
    @Override
    public List<NotificationResponse> getUserNotifications(String userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toNotificationResponse)
                .toList();
    }

    /**
     * Đặt trạng thái {@code SENT} và ghi thời điểm gửi.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     */
    @Override
    public NotificationResponse markAsSent(String id) {
        Notification notification = findNotification(id);
        notification.setStatus(NotificationStatus.SENT);
        notification.setSentAt(LocalDateTime.now());
        return toNotificationResponse(notificationRepository.save(notification));
    }

    /**
     * Đặt trạng thái {@code FAILED}.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     */
    @Override
    public NotificationResponse markAsFailed(String id) {
        Notification notification = findNotification(id);
        notification.setStatus(NotificationStatus.FAILED);
        return toNotificationResponse(notificationRepository.save(notification));
    }

    /**
     * Đặt cờ đã đọc cho một thông báo.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     */
    @Override
    public NotificationResponse markAsRead(String id) {
        Notification notification = findNotification(id);
        notification.setReadStatus(true);
        return toNotificationResponse(notificationRepository.save(notification));
    }

    /**
     * Đặt cờ đã đọc cho mọi thông báo của người dùng.
     *
     * @param userId mã người dùng
     * @return danh sách sau cập nhật
     */
    @Override
    public List<NotificationResponse> markAllAsRead(String userId) {
        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (Notification n : notifications) {
            n.setReadStatus(true);
        }
        return notificationRepository.saveAll(notifications).stream()
                .map(this::toNotificationResponse)
                .toList();
    }

    /**
     * Xử lý sự kiện không kèm nội dung hiển thị trong chuông.
     *
     * @param event sự kiện thông báo
     * @return thông báo đã có hoặc vừa tạo
     */
    @Override
    public NotificationResponse processEvent(NotificationEvent event) {
        return processEvent(event, null, null, null);
    }

    /**
     * Như bản không kèm nội dung hiển thị, nhưng mục trong ứng dụng có thêm loại, tiêu đề và nội dung để chuông
     * hiển thị dễ đọc và có thể dẫn tới đúng nơi. Nếu {@code eventId} đã tồn tại thì dùng lại bản ghi cũ.
     *
     * @param event sự kiện thông báo
     * @param type loại hiển thị
     * @param title tiêu đề
     * @param message nội dung
     * @return thông báo đã có hoặc vừa tạo
     */
    @Override
    public NotificationResponse processEvent(NotificationEvent event, String type, String title, String message) {
        NotificationResponse res = notificationRepository.findByEventId(event.eventId())
                .map(this::toNotificationResponse)
                .orElseGet(() -> {
                    Notification notification = Notification.builder()
                            .eventId(event.eventId())
                            .userId(event.userId() == null || event.userId().isBlank()
                                    ? event.recipientEmail() : event.userId())
                            .recipientEmail(event.recipientEmail())
                            .type(type)
                            .title(title)
                            .message(message)
                            .variablesJson(toVariablesJson(event.variables()))
                            .channel(event.channel())
                            .status(NotificationStatus.PENDING)
                            .build();

                    return toNotificationResponse(notificationRepository.save(notification));
                });
        broadcastStompNotification(res);
        return res;
    }

    /**
     * Lưu thông báo đã ở trạng thái {@code SENT}, chưa đọc, kênh {@code EMAIL}, rồi đẩy qua STOMP.
     * Dữ liệu bổ sung bị bỏ nếu không phải JSON object hoặc quá 4000 ký tự.
     *
     * @param userId mã người nhận
     * @param type loại thông báo
     * @param title tiêu đề
     * @param message nội dung
     * @param variablesJson dữ liệu bổ sung JSON, có thể null
     * @return thông báo đã lưu
     */
    @Override
    public NotificationResponse publishDirectNotification(String userId, String type, String title, String message, String variablesJson) {
        Notification notification = Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .message(message)
                .variablesJson(validVariables(variablesJson))
                .readStatus(false)
                .channel(com.haloticket.notification_service.enums.NotificationChannel.EMAIL)
                .status(NotificationStatus.SENT)
                .build();
        Notification saved = notificationRepository.save(notification);
        NotificationResponse response = toNotificationResponse(saved);
        broadcastStompNotification(response);
        return response;
    }

    /**
     * Dữ liệu bổ sung cho web (mã sự kiện, liên kết, trường bị gắn cờ): chỉ giữ khi là JSON object kích thước hợp lý.
     *
     * @param raw chuỗi JSON thô
     * @return chuỗi gốc nếu hợp lệ, ngược lại null
     */
    private String validVariables(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4000) return null;
        try {
            return objectMapper.readTree(raw).isObject() ? raw : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Đẩy thông báo qua STOMP: tới {@code /topic/admin-notifications} nếu gửi cho admin hoặc loại duyệt sự kiện mới,
     * tới {@code /topic/user-notifications/{userId}} nếu người nhận là người dùng thường. Lỗi đẩy bị bỏ qua.
     *
     * @param response thông báo cần đẩy
     */
    private void broadcastStompNotification(NotificationResponse response) {
        try {
            if (messagingTemplate != null && response != null) {
                if ("ADMIN".equalsIgnoreCase(response.getUserId()) || "NEW_EVENT_APPROVAL".equalsIgnoreCase(response.getType())) {
                    messagingTemplate.convertAndSend("/topic/admin-notifications", response);
                }
                if (response.getUserId() != null && !"ADMIN".equalsIgnoreCase(response.getUserId())) {
                    messagingTemplate.convertAndSend("/topic/user-notifications/" + response.getUserId(), response);
                }
            }
        } catch (Exception e) {
            // bỏ qua lỗi khi đẩy qua WebSocket
        }
    }

    /**
     * Tìm thông báo theo mã.
     *
     * @param id mã thông báo
     * @return thông báo
     * @throws NotificationException {@code NOTIFICATION_NOT_FOUND} (404) khi không có
     */
    private Notification findNotification(String id) {
        return notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND,
                        "Notification not found", 404));
    }

    /**
     * Chuyển map biến thành chuỗi JSON; map rỗng hoặc null cho {@code {}}.
     *
     * @param variables biến thay thế
     * @return chuỗi JSON
     * @throws NotificationException {@code VARIABLES_SERIALIZATION_FAILED} (500) khi không serialize được
     */
    private String toVariablesJson(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JsonProcessingException exception) {
            throw new NotificationException(NotificationErrorCode.VARIABLES_SERIALIZATION_FAILED,
                    "Notification variables cannot be serialized", 500, exception);
        }
    }

    /**
     * Chuyển entity mẫu thành DTO phản hồi.
     *
     * @param template entity mẫu
     * @return DTO phản hồi
     */
    private NotificationTemplateResponse toTemplateResponse(NotificationTemplate template) {
        return NotificationTemplateResponse.builder()
                .id(template.getId())
                .type(template.getType())
                .channel(template.getChannel())
                .subjectTemplate(template.getSubjectTemplate())
                .bodyTemplate(template.getBodyTemplate())
                .status(template.getStatus())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }

    /**
     * Chuyển entity thông báo thành DTO phản hồi; {@code templateId} rỗng khi thông báo không có mẫu.
     *
     * @param notification entity thông báo
     * @return DTO phản hồi
     */
    private NotificationResponse toNotificationResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .eventId(notification.getEventId())
                .templateId(notification.getTemplate() == null ? null : notification.getTemplate().getId())
                .userId(notification.getUserId())
                .recipientEmail(notification.getRecipientEmail())
                .variablesJson(notification.getVariablesJson())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .type(notification.getType())
                .readStatus(notification.getReadStatus())
                .channel(notification.getChannel())
                .status(notification.getStatus())
                .createdAt(notification.getCreatedAt())
                .sentAt(notification.getSentAt())
                .build();
    }
}
