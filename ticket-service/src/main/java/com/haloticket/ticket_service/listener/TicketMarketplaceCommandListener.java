package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.OutboxEvent;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketOperation;
import com.haloticket.ticket_service.enums.OutboxStatus;
import com.haloticket.ticket_service.enums.TicketOperationState;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.OutboxEventRepository;
import com.haloticket.ticket_service.repository.TicketOperationRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Nhận các lệnh của saga chợ vé (khóa, mở khóa, chuyển quyền sở hữu) từ Kafka, cập nhật vé và ghi {@code TicketOperation}.
 * Kết quả được ghi vào bảng outbox để dịch vụ chợ vé nhận lại. Các lệnh idempotent theo {@code operationId}.
 */
@Component
@RequiredArgsConstructor
public class TicketMarketplaceCommandListener {
    private final ObjectMapper objectMapper;
    private final TicketRepository ticketRepository;
    private final TicketOperationRepository operationRepository;
    private final OutboxEventRepository outboxRepository;

    /**
     * Xử lý lệnh khóa vé để bán lại hoặc tặng. Vé hợp lệ phải thuộc người bán, đang {@code VALID}, chưa bị khóa và (với quà tặng)
     * chưa vượt giới hạn số lần tặng. Chỉ cần một vé không đạt thì cả nhóm bị từ chối. Gửi lại cùng lệnh sẽ trả lại kết quả cũ.
     *
     * @param message nội dung JSON của lệnh khóa vé
     */
    @KafkaListener(topics = "haloticket.ticket.lock.requested.v1", groupId = "ticket-service-marketplace")
    @Transactional
    public void lock(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String operationId = root.path("operationId").asText(root.path("sagaId").asText());
            List<String> ids = ids(root);
            if (operationId.isBlank()) throw new IllegalArgumentException("Lock command requires operationId");
            List<TicketOperation> existing = operations(operationId, ids);
            if (!existing.isEmpty()) { emitResult(existing.get(0), existing.size() == ids.size() && existing.stream().allMatch(op -> op.getState() == TicketOperationState.LOCKED) ? "TICKET_LOCKED" : "TICKET_LOCK_REJECTED"); return; }
            List<Ticket> tickets = lockedTickets(ids);
            String owner = text(root, "sellerId");
            String purpose = root.path("purpose").asText("RESALE");
            int maxGiftCount = "GIFT".equals(purpose) ? maxGiftCount(root) : 0;
            boolean valid = tickets.size() == ids.size() && owner != null && tickets.stream().allMatch(ticket -> owner.equals(ticket.getOwnerId())
                    && ticket.getStatus() == TicketStatus.VALID && ticket.getLockOperationId() == null
                    && (maxGiftCount == 0 || ticket.getGiftTransferCount() < maxGiftCount));
            List<TicketOperation> created = new ArrayList<>();
            for (String id : ids) {
                TicketOperation operation = operation(root, memberOperationId(operationId, id, ids), id, owner,
                        root.path("purpose").asText("RESALE"), valid ? TicketOperationState.LOCKED : TicketOperationState.REJECTED);
                if (!valid) operation.setLastError("TICKET_NOT_ELIGIBLE");
                created.add(operationRepository.save(operation));
            }
            if (valid) for (Ticket ticket : tickets) {
                ticket.setStatus("GIFT".equals(root.path("purpose").asText()) ? TicketStatus.LOCKED_FOR_TRANSFER : TicketStatus.LOCKED_FOR_RESALE);
                ticket.setLockOperationId(memberOperationId(operationId, ticket.getId(), ids)); ticketRepository.save(ticket);
            }
            emitResult(created.get(0), valid ? "TICKET_LOCKED" : "TICKET_LOCK_REJECTED");
        } catch (Exception exception) { throw new IllegalStateException("Failed to process ticket lock command", exception); }
    }

    /**
     * Xử lý lệnh mở khóa vé: đưa vé đang khóa bởi đúng thao tác khóa về {@code VALID} và đánh dấu thao tác là {@code RELEASED}.
     *
     * @param message nội dung JSON của lệnh mở khóa
     */
    @KafkaListener(topics = "haloticket.ticket.release.requested.v1", groupId = "ticket-service-marketplace")
    @Transactional
    public void release(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String operationId = root.path("operationId").asText(root.path("sagaId").asText());
            List<String> ids = ids(root);
            List<Ticket> tickets = lockedTickets(ids);
            TicketOperation first = null;
            for (String id : ids) {
                String memberId = memberOperationId(operationId, id, root.path("lockTicketIds").isArray() ? lockIds(root) : ids);
                TicketOperation operation = operationRepository.findByOperationId(memberId).orElse(null);
                if (operation == null) operation = operation(root, memberId, id, null, "RELEASE", TicketOperationState.RELEASED);
                Ticket ticket = tickets.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
                if (ticket != null && memberId.equals(ticket.getLockOperationId())
                        && (ticket.getStatus() == TicketStatus.LOCKED_FOR_RESALE || ticket.getStatus() == TicketStatus.LOCKED_FOR_TRANSFER)) {
                    ticket.setStatus(TicketStatus.VALID); ticket.setLockOperationId(null); ticketRepository.save(ticket);
                }
                operation.setState(TicketOperationState.RELEASED);
                operation = operationRepository.save(operation);
                if (first == null) first = operation;
            }
            emitResult(first, "TICKET_RELEASED");
        } catch (Exception exception) { throw new IllegalStateException("Failed to process ticket release command", exception); }
    }

    /**
     * Xử lý lệnh chuyển quyền sở hữu vé (tặng hoặc bán lại). Chỉ chuyển khi mọi vé còn thuộc người gửi, đang khóa đúng mục đích
     * bởi đúng thao tác khóa. Khi thành công vé đổi chủ, tăng phiên bản QR, đổi mã QR; quà tặng tăng số lần tặng,
     * bán lại ghi giá mua mới.
     *
     * @param message nội dung JSON của lệnh chuyển quyền sở hữu
     */
    @KafkaListener(topics = "haloticket.ticket.ownership.transfer.requested.v1", groupId = "ticket-service-marketplace")
    @Transactional
    public void transfer(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String operationId = root.path("operationId").asText(root.path("sagaId").asText() + ":TRANSFER");
            List<String> ids = ids(root); String fromUserId = text(root, "fromUserId"); String toUserId = text(root, "toUserId");
            if (operationId.isBlank() || fromUserId == null || toUserId == null) throw new IllegalArgumentException("Transfer command is incomplete");
            List<TicketOperation> existing = operations(operationId, ids);
            if (!existing.isEmpty()) { emitResult(existing.get(0), existing.size() == ids.size() && existing.stream().allMatch(op -> op.getState() == TicketOperationState.TRANSFERRED) ? "TICKET_OWNERSHIP_TRANSFERRED" : "TICKET_TRANSFER_REJECTED"); return; }
            String purpose = root.path("purpose").asText(root.path("transferType").asText("GIFT"));
            if (!"GIFT".equals(purpose) && !"RESALE".equals(purpose)) throw new IllegalArgumentException("Unknown transfer purpose");
            int maxGiftCount = "GIFT".equals(purpose) ? maxGiftCount(root) : 0;
            List<Ticket> tickets = lockedTickets(ids);
            String lockOperationId = text(root, "lockOperationId");
            TicketStatus requiredStatus = "RESALE".equals(purpose) ? TicketStatus.LOCKED_FOR_RESALE : TicketStatus.LOCKED_FOR_TRANSFER;
            boolean valid = tickets.size() == ids.size() && lockOperationId != null && tickets.stream().allMatch(ticket ->
                    fromUserId.equals(ticket.getOwnerId()) && ticket.getStatus() == requiredStatus
                            && memberOperationId(lockOperationId, ticket.getId(), root.path("lockTicketIds").isArray() ? lockIds(root) : ids).equals(ticket.getLockOperationId())
                            && (maxGiftCount == 0 || ticket.getGiftTransferCount() < maxGiftCount));
            List<TicketOperation> created = new ArrayList<>();
            for (String id : ids) {
                TicketOperation operation = operation(root, memberOperationId(operationId, id, ids), id, fromUserId, purpose,
                        valid ? TicketOperationState.TRANSFERRED : TicketOperationState.REJECTED);
                if (!valid) operation.setLastError("TICKET_TRANSFER_NOT_ELIGIBLE");
                created.add(operationRepository.save(operation));
            }
            if (valid) for (Ticket ticket : tickets) {
                ticket.setOwnerId(toUserId); ticket.setStatus(TicketStatus.VALID); ticket.setLockOperationId(null); ticket.setQrVersion((ticket.getQrVersion() == null ? 0 : ticket.getQrVersion()) + 1); ticket.setQrCodeHash(UUID.randomUUID().toString());
                if ("RESALE".equals(purpose)) {
                    ticket.setPurchasePrice(root.hasNonNull("purchasePrice") ? root.get("purchasePrice").decimalValue() : ticket.getOriginalPrice());
                    ticket.setAcquisitionType(root.path("acquisitionType").asText("RESALE"));
                    ticket.setBookingId(root.path("purchaseReference").asText(ticket.getBookingId()));
                } else {
                    ticket.setAcquisitionType("GIFT");
                    ticket.setGiftTransferCount(Math.addExact(ticket.getGiftTransferCount(), 1));
                }
                ticketRepository.save(ticket);
            }
            emitResult(created.get(0), valid ? "TICKET_OWNERSHIP_TRANSFERRED" : "TICKET_TRANSFER_REJECTED");
        } catch (Exception exception) { throw new IllegalStateException("Failed to process ticket ownership transfer command", exception); }
    }

    /**
     * Ghi một dòng outbox báo kết quả của thao tác để dịch vụ chợ vé nhận, chọn topic theo loại sự kiện.
     *
     * @param operation thao tác đại diện cho cả nhóm vé
     * @param eventType loại kết quả, ví dụ {@code TICKET_LOCKED} hoặc {@code TICKET_TRANSFER_REJECTED}
     */
    private void emitResult(TicketOperation operation, String eventType) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            String resultState = switch (eventType) {
                case "TICKET_LOCKED" -> "LOCKED";
                case "TICKET_OWNERSHIP_TRANSFERRED" -> "TRANSFERRED";
                case "TICKET_RELEASED" -> "RELEASED";
                default -> "REJECTED";
            };
            payload.put("messageId", java.util.UUID.randomUUID().toString()); payload.put("operationId", operation.getOperationId());
            payload.put("sagaId", operation.getSagaId() != null ? operation.getSagaId() : operation.getOperationId()); payload.put("ticketId", operation.getTicketId()); payload.put("state", resultState);
            payload.put("ticketStatus", resultState);
            String topic = eventType.startsWith("TICKET_LOCK") ? "haloticket.ticket.locked.v1" : eventType.startsWith("TICKET_RELEASE") ? "haloticket.ticket.released.v1" : eventType.startsWith("TICKET_OWNERSHIP") ? "haloticket.ticket.ownership.transferred.v1" : "haloticket.ticket.ownership.transferred.v1";
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_OPERATION").aggregateId(operation.getOperationId()).eventType(eventType)
                    .topic(topic)
                    .payload(objectMapper.writeValueAsString(payload)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (Exception exception) { throw new IllegalStateException("Could not write ticket command result", exception); }
    }

    /**
     * Đọc danh sách mã vé của lệnh ({@code ticketIds} hoặc {@code ticketId}).
     *
     * @param root nội dung JSON của lệnh
     * @return danh sách mã vé
     * @throws IllegalArgumentException khi rỗng, quá 50 vé, có mã trống hoặc trùng
     */
    private List<String> ids(JsonNode root) {
        List<String> result = new ArrayList<>();
        if (root.path("ticketIds").isArray()) root.path("ticketIds").forEach(node -> result.add(node.asText()));
        else result.add(root.path("ticketId").asText());
        if (result.isEmpty() || result.size() > 50 || result.stream().anyMatch(String::isBlank)
                || new HashSet<>(result).size() != result.size()) throw new IllegalArgumentException("Invalid ticketIds");
        return result;
    }

    /**
     * Đọc danh sách mã vé của thao tác khóa trước đó ({@code lockTicketIds}).
     *
     * @param root nội dung JSON của lệnh
     * @return danh sách mã vé đã khóa
     */
    private List<String> lockIds(JsonNode root) {
        List<String> result = new ArrayList<>();
        root.path("lockTicketIds").forEach(node -> result.add(node.asText()));
        return result;
    }

    /**
     * Nạp các vé bằng khóa ghi trong DB theo thứ tự id tăng dần để tránh deadlock; vé không tồn tại bị bỏ qua.
     *
     * @param ids mã các vé cần nạp
     * @return các vé tìm thấy
     */
    private List<Ticket> lockedTickets(List<String> ids) {
        List<Ticket> tickets = new ArrayList<>();
        ids.stream().sorted().forEach(id -> ticketRepository.findWithLockById(id).ifPresent(tickets::add));
        return tickets;
    }

    /**
     * Tìm các thao tác đã ghi của một lệnh, dùng để nhận ra lệnh gửi lại.
     *
     * @param base mã thao tác gốc
     * @param ids mã các vé trong lệnh
     * @return các thao tác đã tồn tại
     */
    private List<TicketOperation> operations(String base, List<String> ids) {
        List<TicketOperation> found = new ArrayList<>();
        for (String id : ids) operationRepository.findByOperationId(memberOperationId(base, id, ids)).ifPresent(found::add);
        return found;
    }

    /**
     * Tạo mã thao tác cho từng vé: giữ nguyên mã gốc khi lệnh chỉ có một vé, ngược lại ghép thêm mã vé.
     *
     * @param base mã thao tác gốc
     * @param id mã vé
     * @param ids mã các vé trong lệnh
     * @return mã thao tác của vé đó
     */
    private static String memberOperationId(String base, String id, List<String> ids) {
        return ids.size() == 1 ? base : base + ":" + id;
    }

    /**
     * Dựng một bản ghi thao tác (chưa lưu) cho một vé.
     *
     * @param root nội dung JSON của lệnh, lấy {@code sagaId}
     * @param id mã thao tác
     * @param ticketId mã vé
     * @param actor người thực hiện, cũng là chủ vé mong đợi
     * @param purpose mục đích ({@code RESALE}, {@code GIFT}, {@code RELEASE})
     * @param state trạng thái thao tác
     * @return bản ghi thao tác mới
     */
    private static TicketOperation operation(JsonNode root, String id, String ticketId, String actor, String purpose, TicketOperationState state) {
        return TicketOperation.builder().operationId(id).sagaId(root.path("sagaId").asText(id)).ticketId(ticketId)
                .actorId(actor).expectedOwnerId(actor).purpose(purpose).state(state).build();
    }

    /** Đọc trường văn bản của nút JSON, trả null nếu thiếu hoặc null. */
    private static String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asText() : null; }

    /**
     * Đọc giới hạn số lần tặng vé; mặc định 3 khi lệnh không nêu.
     *
     * @param root nội dung JSON của lệnh
     * @return số lần tặng tối đa
     * @throws IllegalArgumentException khi giá trị không phải số nguyên không âm
     */
    private static int maxGiftCount(JsonNode root) {
        JsonNode limit = root.get("maxGiftCount");
        if (limit == null || limit.isNull()) return 3;
        if (!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.intValue() < 0)
            throw new IllegalArgumentException("maxGiftCount must be a non-negative integer");
        return limit.intValue();
    }
}
