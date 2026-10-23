package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.client.EventServiceClient;
import com.haloticket.ticket_service.config.CheckinWindowProperties;
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
import com.haloticket.ticket_service.exception.CheckinException;
import com.haloticket.ticket_service.repository.CheckinRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.repository.CheckInAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinService {

    private final CheckinRepository checkinRepository;
    private final TicketRepository ticketRepository;
    private final CheckInAttemptRepository checkInAttemptRepository;
    private final EventScheduleService eventScheduleService;
    private final CheckinWindowProperties checkinWindowProperties;

    private void recordAttempt(Ticket ticket, CheckinRequest request, CheckInAttemptResult result, String failureReason) {
        String staffId = request != null ? request.getStaffAssignmentId() : null;
        if (staffId == null || staffId.trim().isEmpty()) {
            staffId = "UNKNOWN_STAFF";
        }
        
        String device = request != null ? request.getDeviceInfo() : null;
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

    private void validateCheckinWindow(Ticket ticket, CheckinRequest request) {
        try {
            EventServiceClient.EventDetailsResponse eventDetails = eventScheduleService.getEventSchedule(ticket.getEventId());
            if (eventDetails != null) {
                LocalDateTime start = eventDetails.startTime();
                LocalDateTime end = eventDetails.endTime();

                String ticketShowtimeId = ticket.getShowtimeId() != null ? ticket.getShowtimeId()
                        : (ticket.getTicketType() != null ? ticket.getTicketType().getShowtimeId() : null);
                if (ticketShowtimeId != null && eventDetails.showtimes() != null) {
                    for (EventServiceClient.ShowtimeDetailsDto st : eventDetails.showtimes()) {
                        if (ticketShowtimeId.equals(st.id())) {
                            if (st.startTime() != null) start = st.startTime();
                            if (st.endTime() != null) end = st.endTime();
                            break;
                        }
                    }
                }

                if (start != null) {
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime windowOpen = start.minusMinutes(checkinWindowProperties.getOpenBeforeMinutes());
                    LocalDateTime windowClose = (end != null ? end : start.plusHours(4))
                            .plusMinutes(checkinWindowProperties.getCloseAfterEndMinutes());

                    if (now.isBefore(windowOpen) || now.isAfter(windowClose)) {
                        log.warn("Check-in outside window for ticket {}. Window: [{} to {}], Now: {}",
                                ticket.getId(), windowOpen, windowClose, now);
                        recordAttempt(ticket, request, CheckInAttemptResult.OUTSIDE_CHECKIN_WINDOW, "Outside checkin window");
                        throw new CheckinException("OUTSIDE_CHECKIN_WINDOW", "Outside check-in window");
                    }
                }
            }
        } catch (CheckinException ce) {
            throw ce;
        } catch (Exception e) {
            log.warn("Failed to retrieve event schedule for window validation: {}", e.getMessage());
            if (!checkinWindowProperties.isFailOpen()) {
                recordAttempt(ticket, request, CheckInAttemptResult.OUTSIDE_CHECKIN_WINDOW, "Check-in window verification failed (fail-open=false)");
                throw new CheckinException("OUTSIDE_CHECKIN_WINDOW", "Check-in window verification failed");
            }
        }
    }

    @Transactional
    public TicketCheckinDTO processCheckin(CheckinRequest request) {
        Ticket ticket = ticketRepository.findByQrCodeHash(request.getQrCodeHash())
                .orElse(null);

        if (ticket == null) {
            recordAttempt(null, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket not found");
            throw new CheckinException("TICKET_NOT_FOUND", "Ticket not found");
        }

        if (!ticket.getEventId().equals(request.getEventId())) {
            recordAttempt(ticket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket does not belong to this event");
            throw new CheckinException("WRONG_EVENT", "Ticket does not belong to this event");
        }
        
        if (request.getShowtimeId() != null && !request.getShowtimeId().isBlank()) {
            String ticketShowtime = ticket.getShowtimeId() != null ? ticket.getShowtimeId()
                    : (ticket.getTicketType() != null ? ticket.getTicketType().getShowtimeId() : null);
            if (ticketShowtime != null && !ticketShowtime.equals(request.getShowtimeId())) {
                recordAttempt(ticket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket does not belong to this showtime");
                throw new CheckinException("WRONG_SHOWTIME", "Ticket does not belong to this showtime");
            }
        }

        if (checkinWindowProperties.isEnabled()) {
            validateCheckinWindow(ticket, request);
        }

        if (ticket.getStatus() == TicketStatus.USED) {
            recordAttempt(ticket, request, CheckInAttemptResult.FAILED_ALREADY_IN, "Ticket already checked in");
            Checkin existingCheckin = checkinRepository.findByTicketId(ticket.getId()).orElse(null);
            if (existingCheckin != null) {
                return mapToCheckinDTO(existingCheckin, infoOf(ticket), CheckInStatus.DUPLICATED, "ALREADY_USED");
            }
            return buildFallbackDTO(infoOf(ticket), CheckInStatus.DUPLICATED, "ALREADY_USED");
        }

        if (ticket.getStatus() != TicketStatus.VALID) {
            recordAttempt(ticket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket status is not VALID: " + ticket.getStatus());
            // CRITICAL: Do NOT insert into checkins when status is INVALID!
            return buildFallbackDTO(infoOf(ticket), CheckInStatus.INVALID, "TICKET_LOCKED");
        }

        // A VALID ticket that already has a check-in row must not get a second one (checkins.ticket_id is unique).
        // Looking first keeps this a normal answer: a unique-key error raised at commit time would be a 500.
        Checkin alreadyCheckedIn = checkinRepository.findByTicketId(ticket.getId()).orElse(null);
        if (alreadyCheckedIn != null) {
            recordAttempt(ticket, request, CheckInAttemptResult.FAILED_ALREADY_IN, "Ticket already has a check-in record");
            return mapToCheckinDTO(alreadyCheckedIn, infoOf(ticket), CheckInStatus.DUPLICATED, "ALREADY_USED");
        }

        // Read everything the response needs now: the bulk UPDATE below clears the persistence context, which detaches
        // the ticket together with its not yet loaded ticket type and seat.
        TicketInfo info = infoOf(ticket);

        // Ticket status is VALID -> perform atomic conditional update
        LocalDateTime now = LocalDateTime.now();
        int rowsUpdated = ticketRepository.markTicketUsedIfValid(ticket.getId(), now);

        if (rowsUpdated == 1) {
            // Re-attach the ticket (it now reads USED) so the new rows below reference a managed entity.
            Ticket current = ticketRepository.findById(ticket.getId()).orElse(ticket);
            try {
                Checkin checkin = Checkin.builder()
                        .ticket(current)
                        .eventId(current.getEventId())
                        .staffAssignmentId(request.getStaffAssignmentId())
                        .status(CheckInStatus.VALID)
                        .checkedInAt(now)
                        .build();
                checkinRepository.save(checkin);
                recordAttempt(current, request, CheckInAttemptResult.SUCCESS, null);
                return mapToCheckinDTO(checkin, info, null, "SUCCESS");
            } catch (DataIntegrityViolationException dive) {
                log.warn("Concurrent duplicate checkin caught by unique constraint for ticket {}", ticket.getId());
                recordAttempt(current, request, CheckInAttemptResult.FAILED_ALREADY_IN, "Duplicate checkin caught by constraint");
                Checkin existingCheckin = checkinRepository.findByTicketId(ticket.getId()).orElse(null);
                if (existingCheckin != null) {
                    return mapToCheckinDTO(existingCheckin, info, CheckInStatus.DUPLICATED, "ALREADY_USED");
                }
                return buildFallbackDTO(info, CheckInStatus.DUPLICATED, "ALREADY_USED");
            }
        } else {
            // rowsUpdated == 0: Ticket status changed between our read and update (concurrent scan/transfer)
            Ticket refreshedTicket = ticketRepository.findById(ticket.getId()).orElse(ticket);
            TicketInfo refreshed = refreshedTicket == ticket ? info : infoOf(refreshedTicket);
            if (refreshedTicket.getStatus() == TicketStatus.USED) {
                recordAttempt(refreshedTicket, request, CheckInAttemptResult.FAILED_ALREADY_IN, "Ticket already checked in (concurrent)");
                Checkin existingCheckin = checkinRepository.findByTicketId(refreshedTicket.getId()).orElse(null);
                if (existingCheckin != null) {
                    return mapToCheckinDTO(existingCheckin, refreshed, CheckInStatus.DUPLICATED, "ALREADY_USED");
                }
                return buildFallbackDTO(refreshed, CheckInStatus.DUPLICATED, "ALREADY_USED");
            } else {
                recordAttempt(refreshedTicket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket status is not VALID: " + refreshedTicket.getStatus());
                return buildFallbackDTO(refreshed, CheckInStatus.INVALID, "TICKET_LOCKED");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<TicketCheckinDTO> getCheckinsByEvent(String eventId, String showtimeId) {
        List<Checkin> checkins;
        if (showtimeId != null && !showtimeId.isBlank()) {
            checkins = checkinRepository.findByEventIdOrderByCheckedInAtDesc(eventId).stream()
                    .filter(c -> {
                        String tShowtimeId = c.getTicket().getShowtimeId() != null 
                            ? c.getTicket().getShowtimeId() 
                            : (c.getTicket().getTicketType() != null ? c.getTicket().getTicketType().getShowtimeId() : null);
                        return showtimeId.equals(tShowtimeId);
                    })
                    .collect(Collectors.toList());
        } else {
            checkins = checkinRepository.findByEventIdOrderByCheckedInAtDesc(eventId);
        }
        return checkins.stream()
                .map(c -> mapToCheckinDTO(c, infoOf(c.getTicket()), null, c.getStatus() == CheckInStatus.VALID ? "SUCCESS" : c.getStatus().name()))
                .collect(Collectors.toList());
    }

    private String formatSeatName(Ticket ticket) {
        if (ticket.getSeat() != null) {
            Seat seat = ticket.getSeat();
            SeatRow row = seat.getSeatRow();
            SeatZone zone = row != null ? row.getSeatZone() : null;
            
            boolean hasValidRow = row != null && row.getLabel() != null && !row.getLabel().trim().isEmpty() && !row.getLabel().equalsIgnoreCase("STANDING");
            
            if (!hasValidRow) {
                return "Số thứ tự vé/ghế: " + seat.getSeatNumber();
            } else if (zone != null) {
                if (row.getLabel().toUpperCase().contains("BÀN")) {
                    return String.format("Khu %s - %s - Ghế %s", zone.getName(), row.getLabel(), seat.getSeatNumber());
                } else {
                    return String.format("Khu %s - Hàng %s - Ghế %s", zone.getName(), row.getLabel(), seat.getSeatNumber());
                }
            } else {
                return String.format("Hàng %s - Ghế %s", row.getLabel(), seat.getSeatNumber());
            }
        } else {
            return "Số thứ tự vé: " + (ticket.getId() != null ? ticket.getId().split("-")[0].toUpperCase() : "");
        }
    }

    /** What the responses show about a ticket, read while the ticket is still attached to the persistence context. */
    private record TicketInfo(String id, String qrCodeHash, String ownerId, String ticketTypeName, String seatName,
                              String showtimeId, LocalDateTime usedAt) {}

    private TicketInfo infoOf(Ticket ticket) {
        String ticketTypeName = ticket.getTicketType() != null ? ticket.getTicketType().getName() : null;
        String showtimeId = ticket.getShowtimeId() != null ? ticket.getShowtimeId()
                : (ticket.getTicketType() != null ? ticket.getTicketType().getShowtimeId() : null);
        return new TicketInfo(ticket.getId(), ticket.getQrCodeHash(), ticket.getOwnerId(), ticketTypeName,
                formatSeatName(ticket), showtimeId, ticket.getUsedAt());
    }

    private TicketCheckinDTO buildFallbackDTO(TicketInfo ticket, CheckInStatus status, String code) {
        return TicketCheckinDTO.builder()
                .ticketId(ticket.id())
                .qrCodeHash(ticket.qrCodeHash())
                .seatName(ticket.seatName())
                .ticketTypeName(ticket.ticketTypeName())
                .status(status.name())
                .code(code)
                .checkedInAt(ticket.usedAt() != null ? ticket.usedAt() : LocalDateTime.now())
                .showtimeId(ticket.showtimeId())
                .ownerId(ticket.ownerId())
                .build();
    }

    private TicketCheckinDTO mapToCheckinDTO(Checkin checkin, TicketInfo ticket, CheckInStatus overrideStatus, String code) {
        return TicketCheckinDTO.builder()
                .checkinId(checkin.getId())
                .ticketId(ticket.id())
                .qrCodeHash(ticket.qrCodeHash())
                .seatName(ticket.seatName())
                .ticketTypeName(ticket.ticketTypeName())
                .status(overrideStatus != null ? overrideStatus.name() : checkin.getStatus().name())
                .code(code != null ? code : (checkin.getStatus() == CheckInStatus.VALID ? "SUCCESS" : checkin.getStatus().name()))
                .checkedInAt(checkin.getCheckedInAt())
                .showtimeId(ticket.showtimeId())
                .ownerId(ticket.ownerId())
                .build();
    }
}
