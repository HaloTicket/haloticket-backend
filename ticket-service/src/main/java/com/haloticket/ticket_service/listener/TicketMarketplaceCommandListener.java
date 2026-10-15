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

@Component
@RequiredArgsConstructor
public class TicketMarketplaceCommandListener {
    private final ObjectMapper objectMapper;
    private final TicketRepository ticketRepository;
    private final TicketOperationRepository operationRepository;
    private final OutboxEventRepository outboxRepository;

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

    private List<String> ids(JsonNode root) {
        List<String> result = new ArrayList<>();
        if (root.path("ticketIds").isArray()) root.path("ticketIds").forEach(node -> result.add(node.asText()));
        else result.add(root.path("ticketId").asText());
        if (result.isEmpty() || result.size() > 50 || result.stream().anyMatch(String::isBlank)
                || new HashSet<>(result).size() != result.size()) throw new IllegalArgumentException("Invalid ticketIds");
        return result;
    }

    private List<String> lockIds(JsonNode root) {
        List<String> result = new ArrayList<>();
        root.path("lockTicketIds").forEach(node -> result.add(node.asText()));
        return result;
    }

    private List<Ticket> lockedTickets(List<String> ids) {
        List<Ticket> tickets = new ArrayList<>();
        ids.stream().sorted().forEach(id -> ticketRepository.findWithLockById(id).ifPresent(tickets::add));
        return tickets;
    }

    private List<TicketOperation> operations(String base, List<String> ids) {
        List<TicketOperation> found = new ArrayList<>();
        for (String id : ids) operationRepository.findByOperationId(memberOperationId(base, id, ids)).ifPresent(found::add);
        return found;
    }

    private static String memberOperationId(String base, String id, List<String> ids) {
        return ids.size() == 1 ? base : base + ":" + id;
    }

    private static TicketOperation operation(JsonNode root, String id, String ticketId, String actor, String purpose, TicketOperationState state) {
        return TicketOperation.builder().operationId(id).sagaId(root.path("sagaId").asText(id)).ticketId(ticketId)
                .actorId(actor).expectedOwnerId(actor).purpose(purpose).state(state).build();
    }

    private static String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asText() : null; }

    private static int maxGiftCount(JsonNode root) {
        JsonNode limit = root.get("maxGiftCount");
        if (limit == null || limit.isNull()) return 3;
        if (!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.intValue() < 0)
            throw new IllegalArgumentException("maxGiftCount must be a non-negative integer");
        return limit.intValue();
    }
}
