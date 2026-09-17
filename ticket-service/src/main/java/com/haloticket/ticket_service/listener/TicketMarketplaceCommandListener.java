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
            String ticketId = root.path("ticketId").asText();
            if (operationId.isBlank() || ticketId.isBlank()) throw new IllegalArgumentException("Lock command requires operationId and ticketId");
            TicketOperation existing = operationRepository.findByOperationId(operationId).orElse(null);
            if (existing != null) { emitResult(existing, existing.getState() == TicketOperationState.LOCKED ? "TICKET_LOCKED" : "TICKET_LOCK_REJECTED"); return; }
            TicketOperation operation = TicketOperation.builder().operationId(operationId).sagaId(root.path("sagaId").asText(operationId)).ticketId(ticketId).actorId(text(root,"sellerId"))
                    .expectedOwnerId(text(root,"sellerId")).purpose(root.path("purpose").asText("RESALE"))
                    .state(TicketOperationState.REQUESTED).build();
            Ticket ticket = ticketRepository.findWithLockById(ticketId).orElse(null);
            if (ticket == null || !ticket.getOwnerId().equals(operation.getExpectedOwnerId()) || ticket.getStatus() != TicketStatus.VALID || ticket.getLockOperationId() != null) {
                operation.setState(TicketOperationState.REJECTED); operation.setLastError("TICKET_NOT_ELIGIBLE"); operationRepository.save(operation); emitResult(operation, "TICKET_LOCK_REJECTED"); return;
            }
            ticket.setStatus("GIFT".equals(operation.getPurpose()) ? TicketStatus.LOCKED_FOR_TRANSFER : TicketStatus.LOCKED_FOR_RESALE);
            ticket.setLockOperationId(operationId); ticketRepository.save(ticket);
            operation.setState(TicketOperationState.LOCKED); operationRepository.save(operation); emitResult(operation, "TICKET_LOCKED");
        } catch (Exception exception) { throw new IllegalStateException("Failed to process ticket lock command", exception); }
    }

    @KafkaListener(topics = "haloticket.ticket.release.requested.v1", groupId = "ticket-service-marketplace")
    @Transactional
    public void release(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String operationId = root.path("operationId").asText(root.path("sagaId").asText());
            TicketOperation operation = operationRepository.findByOperationId(operationId).orElse(null);
            if (operation == null) return;
            if (operation.getState() == TicketOperationState.RELEASED) { emitResult(operation, "TICKET_RELEASED"); return; }
            Ticket ticket = ticketRepository.findWithLockById(operation.getTicketId()).orElse(null);
            if (ticket != null && operationId.equals(ticket.getLockOperationId())) {
                if (ticket.getStatus() == TicketStatus.LOCKED_FOR_RESALE || ticket.getStatus() == TicketStatus.LOCKED_FOR_TRANSFER) {
                    ticket.setStatus(TicketStatus.VALID); ticket.setLockOperationId(null); ticketRepository.save(ticket);
                }
            }
            operation.setState(TicketOperationState.RELEASED); operationRepository.save(operation); emitResult(operation, "TICKET_RELEASED");
        } catch (Exception exception) { throw new IllegalStateException("Failed to process ticket release command", exception); }
    }

    @KafkaListener(topics = "haloticket.ticket.ownership.transfer.requested.v1", groupId = "ticket-service-marketplace")
    @Transactional
    public void transfer(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String operationId = root.path("operationId").asText(root.path("sagaId").asText() + ":TRANSFER");
            String ticketId = root.path("ticketId").asText(); String fromUserId = text(root, "fromUserId"); String toUserId = text(root, "toUserId");
            if (operationId.isBlank() || ticketId.isBlank() || fromUserId == null || toUserId == null) throw new IllegalArgumentException("Transfer command is incomplete");
            TicketOperation existing = operationRepository.findByOperationId(operationId).orElse(null);
            if (existing != null) { emitResult(existing, existing.getState() == TicketOperationState.TRANSFERRED ? "TICKET_OWNERSHIP_TRANSFERRED" : "TICKET_TRANSFER_REJECTED"); return; }
            TicketOperation operation = TicketOperation.builder().operationId(operationId).sagaId(root.path("sagaId").asText()).ticketId(ticketId).actorId(fromUserId).expectedOwnerId(fromUserId).purpose("GIFT").state(TicketOperationState.REQUESTED).build();
            Ticket ticket = ticketRepository.findWithLockById(ticketId).orElse(null);
            String lockOperationId = text(root, "lockOperationId");
            if (ticket == null || !fromUserId.equals(ticket.getOwnerId()) || ticket.getStatus() != TicketStatus.LOCKED_FOR_TRANSFER || !root.path("sagaId").asText().equals(lockOperationId) || !lockOperationId.equals(ticket.getLockOperationId())) {
                operation.setState(TicketOperationState.REJECTED); operation.setLastError("TICKET_TRANSFER_NOT_ELIGIBLE"); operationRepository.save(operation); emitResult(operation, "TICKET_TRANSFER_REJECTED"); return;
            }
            ticket.setOwnerId(toUserId); ticket.setStatus(TicketStatus.VALID); ticket.setLockOperationId(null); ticket.setQrVersion((ticket.getQrVersion() == null ? 0 : ticket.getQrVersion()) + 1); ticket.setQrCodeHash(UUID.randomUUID().toString()); ticketRepository.save(ticket);
            operation.setState(TicketOperationState.TRANSFERRED); operationRepository.save(operation); emitResult(operation, "TICKET_OWNERSHIP_TRANSFERRED");
        } catch (Exception exception) { throw new IllegalStateException("Failed to process ticket ownership transfer command", exception); }
    }

    private void emitResult(TicketOperation operation, String eventType) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", java.util.UUID.randomUUID().toString()); payload.put("operationId", operation.getOperationId());
            payload.put("sagaId", operation.getSagaId() != null ? operation.getSagaId() : operation.getOperationId()); payload.put("ticketId", operation.getTicketId()); payload.put("state", operation.getState().name());
            payload.put("ticketStatus", operation.getState() == TicketOperationState.LOCKED ? "LOCKED" : operation.getState().name());
            String topic = eventType.startsWith("TICKET_LOCK") ? "haloticket.ticket.locked.v1" : eventType.startsWith("TICKET_RELEASE") ? "haloticket.ticket.released.v1" : eventType.startsWith("TICKET_OWNERSHIP") ? "haloticket.ticket.ownership.transferred.v1" : "haloticket.ticket.ownership.transferred.v1";
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_OPERATION").aggregateId(operation.getOperationId()).eventType(eventType)
                    .topic(topic)
                    .payload(objectMapper.writeValueAsString(payload)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (Exception exception) { throw new IllegalStateException("Could not write ticket command result", exception); }
    }

    private static String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asText() : null; }
}
