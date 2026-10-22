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
                return mapToCheckinDTO(existingCheckin, ticket, CheckInStatus.DUPLICATED, "ALREADY_USED");
            }
            return buildFallbackDTO(ticket, CheckInStatus.DUPLICATED, "ALREADY_USED");
        }

        if (ticket.getStatus() != TicketStatus.VALID) {
            recordAttempt(ticket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket status is not VALID: " + ticket.getStatus());
            // CRITICAL: Do NOT insert into checkins when status is INVALID!
            return buildFallbackDTO(ticket, CheckInStatus.INVALID, "TICKET_LOCKED");
        }

        // Ticket status is VALID -> perform atomic conditional update
        LocalDateTime now = LocalDateTime.now();
        int rowsUpdated = ticketRepository.markTicketUsedIfValid(ticket.getId(), now);

        if (rowsUpdated == 1) {
            try {
                Checkin checkin = Checkin.builder()
                        .ticket(ticket)
                        .eventId(ticket.getEventId())
                        .staffAssignmentId(request.getStaffAssignmentId())
                        .status(CheckInStatus.VALID)
                        .checkedInAt(now)
                        .build();
                checkinRepository.save(checkin);
                recordAttempt(ticket, request, CheckInAttemptResult.SUCCESS, null);
                return mapToCheckinDTO(checkin, ticket, null, "SUCCESS");
            } catch (DataIntegrityViolationException dive) {
                log.warn("Concurrent duplicate checkin caught by unique constraint for ticket {}", ticket.getId());
                recordAttempt(ticket, request, CheckInAttemptResult.FAILED_ALREADY_IN, "Duplicate checkin caught by constraint");
                Checkin existingCheckin = checkinRepository.findByTicketId(ticket.getId()).orElse(null);
                if (existingCheckin != null) {
                    return mapToCheckinDTO(existingCheckin, ticket, CheckInStatus.DUPLICATED, "ALREADY_USED");
                }
                return buildFallbackDTO(ticket, CheckInStatus.DUPLICATED, "ALREADY_USED");
            }
        } else {
            // rowsUpdated == 0: Ticket status changed between our read and update (concurrent scan/transfer)
            Ticket refreshedTicket = ticketRepository.findById(ticket.getId()).orElse(ticket);
            if (refreshedTicket.getStatus() == TicketStatus.USED) {
                recordAttempt(refreshedTicket, request, CheckInAttemptResult.FAILED_ALREADY_IN, "Ticket already checked in (concurrent)");
                Checkin existingCheckin = checkinRepository.findByTicketId(refreshedTicket.getId()).orElse(null);
                if (existingCheckin != null) {
                    return mapToCheckinDTO(existingCheckin, refreshedTicket, CheckInStatus.DUPLICATED, "ALREADY_USED");
                }
                return buildFallbackDTO(refreshedTicket, CheckInStatus.DUPLICATED, "ALREADY_USED");
            } else {
                recordAttempt(refreshedTicket, request, CheckInAttemptResult.FAILED_SIGNATURE, "Ticket status is not VALID: " + refreshedTicket.getStatus());
                return buildFallbackDTO(refreshedTicket, CheckInStatus.INVALID, "TICKET_LOCKED");
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
                .map(c -> mapToCheckinDTO(c, c.getTicket(), null, c.getStatus() == CheckInStatus.VALID ? "SUCCESS" : c.getStatus().name()))
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

    private TicketCheckinDTO buildFallbackDTO(Ticket ticket, CheckInStatus status, String code) {
        return TicketCheckinDTO.builder()
                .ticketId(ticket.getId())
                .qrCodeHash(ticket.getQrCodeHash())
                .seatName(formatSeatName(ticket))
                .ticketTypeName(ticket.getTicketType() != null ? ticket.getTicketType().getName() : null)
                .status(status.name())
                .code(code)
                .checkedInAt(ticket.getUsedAt() != null ? ticket.getUsedAt() : LocalDateTime.now())
                .showtimeId(ticket.getShowtimeId() != null ? ticket.getShowtimeId() : (ticket.getTicketType() != null ? ticket.getTicketType().getShowtimeId() : null))
                .ownerId(ticket.getOwnerId())
                .build();
    }

    private TicketCheckinDTO mapToCheckinDTO(Checkin checkin, Ticket ticket, CheckInStatus overrideStatus, String code) {
        String seatName = formatSeatName(ticket);
        return TicketCheckinDTO.builder()
                .checkinId(checkin.getId())
                .ticketId(ticket.getId())
                .qrCodeHash(ticket.getQrCodeHash())
                .seatName(seatName)
                .ticketTypeName(ticket.getTicketType() != null ? ticket.getTicketType().getName() : null)
                .status(overrideStatus != null ? overrideStatus.name() : checkin.getStatus().name())
                .code(code != null ? code : (checkin.getStatus() == CheckInStatus.VALID ? "SUCCESS" : checkin.getStatus().name()))
                .checkedInAt(checkin.getCheckedInAt())
                .showtimeId(ticket.getShowtimeId() != null ? ticket.getShowtimeId() : (ticket.getTicketType() != null ? ticket.getTicketType().getShowtimeId() : null))
                .ownerId(ticket.getOwnerId())
                .build();
    }

    private TicketCheckinDTO mapToCheckinDTO(Checkin checkin, Ticket ticket, CheckInStatus overrideStatus) {
        return mapToCheckinDTO(checkin, ticket, overrideStatus, null);
    }
}
