package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.entity.CheckInAttempt;
import com.haloticket.ticket_service.enums.CheckInAttemptResult;
import com.haloticket.ticket_service.repository.CheckInAttemptRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Stores a refused scan in its own transaction. A refused scan ends with a CheckinException, which rolls back the
 * check-in transaction; the attempt row would go with it. Here it commits on its own, and because the write is
 * flushed inside this transaction any database error (for example an outdated CHECK constraint) surfaces here, rolls
 * back only this small transaction, and reaches the caller, which decides how to degrade.
 */
@Component
@RequiredArgsConstructor
public class CheckinAttemptRecorder {

    private final CheckInAttemptRepository attempts;
    private final TicketRepository tickets;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String ticketId, CheckinRequest request, CheckInAttemptResult result, String failureReason) {
        String staffId = request != null ? request.getStaffAssignmentId() : null;
        String device = request != null ? request.getDeviceInfo() : null;
        attempts.saveAndFlush(CheckInAttempt.builder()
                // a reference by id, no extra query; null for a code that matches no ticket (the column is nullable).
                .ticket(ticketId == null ? null : tickets.getReferenceById(ticketId))
                .staffAssignmentId(staffId == null || staffId.trim().isEmpty() ? "UNKNOWN_STAFF" : staffId)
                .deviceInfo(device == null || device.trim().isEmpty() ? "UNKNOWN_DEVICE" : device)
                .result(result)
                .failureReason(failureReason)
                .attemptedAt(LocalDateTime.now())
                .build());
    }
}
