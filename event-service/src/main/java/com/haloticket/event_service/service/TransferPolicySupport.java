package com.haloticket.event_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.TransferPolicyAudit;
import com.haloticket.event_service.entity.TransferPolicyOutboxEvent;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.TransferPolicyAuditRepository;
import com.haloticket.event_service.repository.TransferPolicyOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Các thao tác dùng chung cho chính sách tặng và bán lại vé: kiểm tra hợp lệ, khóa phiên bản lạc quan và ghi nhật ký kiểm toán kèm outbox. */
@Service @RequiredArgsConstructor
public class TransferPolicySupport {
    private static final ZoneId EVENT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final EventRepository events;
    private final TicketServiceClient tickets;
    private final TransferPolicyAuditRepository audits;
    private final TransferPolicyOutboxRepository outbox;
    private final ObjectMapper mapper;

    /**
     * Kiểm tra khung thời gian và phạm vi loại vé của chính sách. Thời điểm mở phải trước thời điểm đóng, thời điểm đóng không được sau mốc kết thúc của sự kiện (hoặc giờ bắt đầu sớm nhất của các suất diễn). Phạm vi {@code ALL} không kèm id; {@code SELECTED} phải có id và mọi id phải là loại vé của sự kiện (hỏi ticket-service).
     * @param eventId id sự kiện
     * @param opensAt thời điểm mở, có thể null
     * @param closesAt thời điểm đóng, có thể null
     * @param scope {@code ALL} hoặc {@code SELECTED}
     * @param ids id loại vé được áp dụng khi phạm vi là {@code SELECTED}
     * @throws ResponseStatusException 404 {@code EVENT_NOT_FOUND}, 422 {@code INVALID_POLICY}, 503 {@code POLICY_SERVICE_UNAVAILABLE} khi không gọi được ticket-service
     */
    public void validate(String eventId, Instant opensAt, Instant closesAt, String scope, Set<String> ids) {
        Event event = events.findById(eventId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
        if (opensAt != null && closesAt != null && !opensAt.isBefore(closesAt)) invalid();
        Instant eventEnd = event.getShowtimes() == null || event.getShowtimes().isEmpty()
                ? event.getEndTime() == null ? null : event.getEndTime().atZone(EVENT_ZONE).toInstant()
                : event.getShowtimes().stream().map(s -> s.getStartTime().atZone(EVENT_ZONE).toInstant()).min(Instant::compareTo).orElse(null);
        if (closesAt != null && eventEnd != null && closesAt.isAfter(eventEnd)) invalid();
        if ("ALL".equals(scope)) {
            if (ids != null && !ids.isEmpty()) invalid();
        } else if ("SELECTED".equals(scope)) {
            if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id.isBlank())) invalid();
            try {
                Object raw = tickets.getTicketTypesByEventId(eventId);
                Set<String> valid = new HashSet<>();
                if (raw instanceof List<?> list) for (Object item : list) {
                    String id = mapper.convertValue(item, com.haloticket.event_service.client.TicketTypeDto.class).getId();
                    if (id != null) valid.add(id);
                }
                if (!valid.containsAll(ids)) invalid();
            } catch (ResponseStatusException failure) { throw failure; }
            catch (Exception failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_SERVICE_UNAVAILABLE", failure); }
        } else invalid();
    }

    /**
     * Kiểm tra khóa lạc quan: phiên bản client gửi lên phải khớp phiên bản hiện tại.
     * @param expected phiên bản client đang giữ
     * @param actual phiên bản hiện tại trong cơ sở dữ liệu
     * @throws ResponseStatusException 409 {@code POLICY_CHANGED} khi thiếu hoặc lệch phiên bản
     */
    public void version(Long expected, Long actual) {
        if (expected == null || !expected.equals(actual)) throw new ResponseStatusException(HttpStatus.CONFLICT, "POLICY_CHANGED");
    }

    /**
     * Ghi nhật ký kiểm toán (trước và sau thay đổi) và một bản ghi outbox {@code PENDING} để phát sự kiện chính sách đã đổi, cùng giao dịch với việc lưu chính sách.
     * @param eventId id sự kiện
     * @param feature loại chính sách, {@code GIFT} hoặc {@code RESALE}
     * @param actor id người thực hiện
     * @param before trạng thái trước, null khi tạo mới
     * @param after trạng thái sau thay đổi
     * @param version phiên bản sau khi lưu
     */
    public void record(String eventId, String feature, String actor, Object before, Object after, long version) {
        Instant now = Instant.now();
        String messageId = UUID.randomUUID().toString();
        String afterJson = json(after);
        audits.save(TransferPolicyAudit.builder().eventId(eventId).feature(feature).actor(actor).version(version)
                .beforeJson(before == null ? null : json(before)).afterJson(afterJson).changedAt(now).build());
        outbox.save(TransferPolicyOutboxEvent.builder().id(messageId).eventId(eventId).status("PENDING")
                .retryCount(0).nextAttemptAt(now).createdAt(now)
                .payload(json(Map.of("messageId", messageId, "eventId", eventId, "feature", feature,
                        "version", version, "rules", after, "changedAt", now))).build());
    }

    /** Chuyển đối tượng sang chuỗi JSON. */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }
    /** Ném lỗi 422 {@code INVALID_POLICY}. */
    private void invalid() { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POLICY"); }
}
