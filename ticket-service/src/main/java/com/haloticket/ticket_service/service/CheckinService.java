package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.TicketCheckinDTO;
import com.haloticket.ticket_service.entity.Checkin;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.Seat;
import com.haloticket.ticket_service.entity.SeatRow;
import com.haloticket.ticket_service.entity.SeatZone;
import com.haloticket.ticket_service.entity.CheckInAttempt;
import com.haloticket.ticket_service.enums.CheckInStatus;
import com.haloticket.ticket_service.enums.CheckInAttemptResult;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.CheckinRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.repository.CheckInAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CheckinService {

    private final CheckinRepository checkinRepository;
    private final TicketRepository ticketRepository;
    private final CheckInAttemptRepository checkInAttemptRepository;

    private void recordAttempt(Ticket ticket, CheckinRequest request, CheckInAttemptResult result, String failureReason) {
        if (ticket == null) return;
        
        String staffId = request.getStaffAssignmentId();
        if (staffId == null || staffId.trim().isEmpty()) {
            staffId = "UNKNOWN_STAFF";
        }
        
        String device = request.getDeviceInfo();
        if (device == null || device.trim().isEmpty()) {
            device = "UNKNOWN_DEVICE";
        }
        
        CheckInAttempt attempt = CheckInAttempt.builder()
                .ticket(ticket)
                .staffAssignmentId(staffId)
                .deviceInfo(device)
                .result(result)
                .failureReason(failureReason)
                .attemptedAt(LocalDateTime.now())
                .build();
        checkInAttemptRepository.save(attempt);
    }

    @Transactional
    public TicketCheckinDTO processCheckin(CheckinRequest request) {
        Ticket ticket = ticketRepository.findByQrCodeHash(request.getQrCodeHash())
                .orElse(null);

        if (ticket == null) {
            // cannot record attempt without ticket, but we just throw
            throw new RuntimeException("Ticket not found");
        }

        if (!ticket.getEventId().equals(request.getEventId())) {
            recordAttempt(ticket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket does not belong to this event");
            throw new RuntimeException("Ticket does not belong to this event");
        }
        
        if (request.getShowtimeId() != null && !request.getShowtimeId().isBlank()) {
            if (ticket.getShowtimeId() != null && !ticket.getShowtimeId().equals(request.getShowtimeId())) {
                recordAttempt(ticket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket does not belong to this showtime");
                throw new RuntimeException("Ticket does not belong to this showtime");
            }
        }

        CheckInStatus checkInStatus;
        if (ticket.getStatus() == TicketStatus.USED) {
            checkInStatus = CheckInStatus.DUPLICATED;
            recordAttempt(ticket, request, CheckInAttemptResult.FAILED_ALREADY_IN, "Ticket already checked in");
            
            Checkin existingCheckin = checkinRepository.findByTicketId(ticket.getId()).orElse(null);
            if (existingCheckin != null) {
                return mapToCheckinDTO(existingCheckin, ticket, checkInStatus);
            }
            // fallback if USED but somehow no checkin record
            return TicketCheckinDTO.builder()
                    .ticketId(ticket.getId())
                    .qrCodeHash(ticket.getQrCodeHash())
                    .seatName(ticket.getSeat() != null ? ticket.getSeat().getSeatNumber() : null)
                    .ticketTypeName(ticket.getTicketType() != null ? ticket.getTicketType().getName() : null)
                    .status(checkInStatus.name())
                    .checkedInAt(ticket.getUsedAt() != null ? ticket.getUsedAt() : LocalDateTime.now())
                    .showtimeId(ticket.getShowtimeId())
                    .ownerId(ticket.getOwnerId())
                    .build();
        } else if (ticket.getStatus() != TicketStatus.VALID) {
            checkInStatus = CheckInStatus.INVALID;
            recordAttempt(ticket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket status is not VALID");
            
            Checkin existingCheckin = checkinRepository.findByTicketId(ticket.getId()).orElse(null);
            if (existingCheckin != null) {
                return mapToCheckinDTO(existingCheckin, ticket, checkInStatus);
            }
            Checkin checkin = Checkin.builder()
                    .ticket(ticket)
                    .eventId(ticket.getEventId())
                    .staffAssignmentId(request.getStaffAssignmentId())
                    .status(checkInStatus)
                    .checkedInAt(LocalDateTime.now())
                    .build();
            checkinRepository.save(checkin);
            return mapToCheckinDTO(checkin, ticket, null);
        } else {
            checkInStatus = CheckInStatus.VALID;
            recordAttempt(ticket, request, CheckInAttemptResult.SUCCESS, null);
            
            ticket.setStatus(TicketStatus.USED);
            ticket.setUsedAt(LocalDateTime.now());
            ticketRepository.save(ticket);
            
            Checkin checkin = Checkin.builder()
                    .ticket(ticket)
                    .eventId(ticket.getEventId())
                    .staffAssignmentId(request.getStaffAssignmentId())
                    .status(checkInStatus)
                    .checkedInAt(ticket.getUsedAt())
                    .build();
            checkinRepository.save(checkin);
            return mapToCheckinDTO(checkin, ticket, null);
        }
    }

    @Transactional(readOnly = true)
    public List<TicketCheckinDTO> getCheckinsByEvent(String eventId, String showtimeId) {
        List<Checkin> checkins;
        if (showtimeId != null && !showtimeId.isBlank()) {
            checkins = checkinRepository.findByEventIdAndTicket_ShowtimeIdOrderByCheckedInAtDesc(eventId, showtimeId);
        } else {
            checkins = checkinRepository.findByEventIdOrderByCheckedInAtDesc(eventId);
        }
        return checkins.stream()
                .map(c -> mapToCheckinDTO(c, c.getTicket(), null))
                .collect(Collectors.toList());
    }

    private TicketCheckinDTO mapToCheckinDTO(Checkin checkin, Ticket ticket, CheckInStatus overrideStatus) {
        String seatName = null;
        if (ticket.getSeat() != null) {
            Seat seat = ticket.getSeat();
            SeatRow row = seat.getSeatRow();
            SeatZone zone = row != null ? row.getSeatZone() : null;
            
            if (zone != null && row != null) {
                seatName = String.format("Khu %s - Hàng %s - Ghế %s", zone.getName(), row.getLabel(), seat.getSeatNumber());
            } else if (row != null) {
                seatName = String.format("Hàng %s - Ghế %s", row.getLabel(), seat.getSeatNumber());
            } else {
                seatName = "Ghế " + seat.getSeatNumber();
            }
        }

        return TicketCheckinDTO.builder()
                .checkinId(checkin.getId())
                .ticketId(ticket.getId())
                .qrCodeHash(ticket.getQrCodeHash())
                .seatName(seatName)
                .ticketTypeName(ticket.getTicketType() != null ? ticket.getTicketType().getName() : null)
                .status(overrideStatus != null ? overrideStatus.name() : checkin.getStatus().name())
                .checkedInAt(checkin.getCheckedInAt())
                .showtimeId(ticket.getShowtimeId())
                .ownerId(ticket.getOwnerId())
                .build();
    }
}
