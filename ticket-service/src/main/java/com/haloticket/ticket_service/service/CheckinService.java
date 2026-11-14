package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.client.EventServiceClient;
import com.haloticket.ticket_service.config.CheckinWindowProperties;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.CheckinPageDTO;
import com.haloticket.ticket_service.dto.response.CheckinSummaryDTO;
import com.haloticket.ticket_service.dto.response.CheckinTicketLookupDTO;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final CheckinAttemptRecorder attemptRecorder;

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

    /**
     * Stores the attempt of a scan that is refused with a CheckinException (or answered as locked). It must outlive
     * the exception, so it is written in its own transaction. It never throws: if the row cannot be stored (for
     * example a CHECK constraint that does not know the value yet) it is logged and the app still gets the business
     * answer it always got.
     */
    private void recordRefusedScan(String ticketId, CheckinRequest request, CheckInAttemptResult result, String failureReason) {
        try {
            attemptRecorder.write(ticketId, request, result, failureReason);
        } catch (Exception e) {
            log.error("[CHECKIN_ATTEMPT_LOST] could not store attempt result={} ticketId={}: {}",
                    result, ticketId, e.getMessage());
        }
    }

    /** Text taken from the request is stored in a log-like column: cut it short and drop control characters. */
    private static String requested(String value) {
        if (value == null) return "-";
        String clean = value.replaceAll("[\\p{Cntrl}]", "?");
        return clean.length() > 60 ? clean.substring(0, 60) + "..." : clean;
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
                        recordRefusedScan(ticket.getId(), request, CheckInAttemptResult.OUTSIDE_CHECKIN_WINDOW, "Outside checkin window");
                        throw new CheckinException("OUTSIDE_CHECKIN_WINDOW", "Outside check-in window");
                    }
                }
            }
        } catch (CheckinException ce) {
            throw ce;
        } catch (Exception e) {
            log.warn("Failed to retrieve event schedule for window validation: {}", e.getMessage());
            if (!checkinWindowProperties.isFailOpen()) {
                recordRefusedScan(ticket.getId(), request, CheckInAttemptResult.WINDOW_UNVERIFIED, "Check-in window verification failed (fail-open=false)");
                throw new CheckinException("OUTSIDE_CHECKIN_WINDOW", "Check-in window verification failed");
            }
        }
    }

    @Transactional
    public TicketCheckinDTO processCheckin(CheckinRequest request) {
        Ticket ticket = ticketRepository.findByQrCodeHash(request.getQrCodeHash())
                .orElse(null);

        if (ticket == null) {
            recordRefusedScan(null, request, CheckInAttemptResult.TICKET_NOT_FOUND,
                    "Ticket not found (event=" + requested(request.getEventId()) + ", showtime=" + requested(request.getShowtimeId()) + ")");
            throw new CheckinException("TICKET_NOT_FOUND", "Ticket not found");
        }

        if (!ticket.getEventId().equals(request.getEventId())) {
            recordRefusedScan(ticket.getId(), request, CheckInAttemptResult.WRONG_EVENT,
                    "Ticket does not belong to this event (scanned at event=" + requested(request.getEventId()) + ")");
            throw new CheckinException("WRONG_EVENT", "Ticket does not belong to this event");
        }
        
        if (request.getShowtimeId() != null && !request.getShowtimeId().isBlank()) {
            String ticketShowtime = ticket.getShowtimeId() != null ? ticket.getShowtimeId()
                    : (ticket.getTicketType() != null ? ticket.getTicketType().getShowtimeId() : null);
            if (ticketShowtime != null && !ticketShowtime.equals(request.getShowtimeId())) {
                recordRefusedScan(ticket.getId(), request, CheckInAttemptResult.WRONG_SHOWTIME,
                        "Ticket does not belong to this showtime (scanned at showtime=" + requested(request.getShowtimeId()) + ")");
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
            recordRefusedScan(ticket.getId(), request, CheckInAttemptResult.TICKET_LOCKED, "Ticket status is not VALID: " + ticket.getStatus());
            // CRITICAL: Do NOT insert into checkins when status is INVALID!
            return buildFallbackDTO(infoOf(ticket), CheckInStatus.INVALID, refusalCode(ticket.getStatus()));
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
                recordRefusedScan(refreshedTicket.getId(), request, CheckInAttemptResult.TICKET_LOCKED, "Ticket status is not VALID: " + refreshedTicket.getStatus());
                return buildFallbackDTO(refreshed, CheckInStatus.INVALID, refusalCode(refreshedTicket.getStatus()));
            }
        }
    }

    @Transactional(readOnly = true)
    public List<TicketCheckinDTO> getCheckinsByEvent(String eventId, String showtimeId) {
        return checkinRepository.listByEvent(eventId, showtimeParam(showtimeId)).stream()
                .map(this::toListItem)
                .collect(Collectors.toList());
    }

    /** One page of the people who entered, newest first; size is kept between 1 and 100. */
    @Transactional(readOnly = true)
    public CheckinPageDTO getCheckinPage(String eventId, String showtimeId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<Checkin> found = checkinRepository.pageByEvent(eventId, showtimeParam(showtimeId), PageRequest.of(Math.max(page, 0), safeSize));
        List<TicketCheckinDTO> content = found.getContent().stream().map(this::toListItem).collect(Collectors.toList());
        return new CheckinPageDTO(content, found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    /** Tickets that still count towards the door: the holder's own tickets, whether they are free to enter or locked for now. */
    private static final List<TicketStatus> DOOR_STATUSES = List.of(TicketStatus.VALID, TicketStatus.USED,
            TicketStatus.LOCKED_FOR_RESALE, TicketStatus.LOCKED_FOR_TRANSFER);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_RESULTS = 30;
    private static final int MIN_SEARCH_LENGTH = 2;

    /** How many tickets the event has for the door and how many entered, in total, per showtime and per ticket type. */
    @Transactional(readOnly = true)
    public CheckinSummaryDTO getSummary(String eventId) {
        Map<String, long[]> showtimes = new LinkedHashMap<>();
        Map<String, long[]> types = new LinkedHashMap<>();
        Map<String, String> typeNames = new LinkedHashMap<>();
        long total = 0;
        long in = 0;
        for (Object[] row : ticketRepository.countByShowtimeTypeAndStatus(eventId, DOOR_STATUSES)) {
            String showtimeId = row[0] != null ? (String) row[0] : "";
            String typeId = (String) row[1];
            long count = ((Number) row[4]).longValue();
            boolean used = row[3] == TicketStatus.USED;
            total += count;
            if (used) in += count;
            long[] byShowtime = showtimes.computeIfAbsent(showtimeId, k -> new long[2]);
            byShowtime[0] += count;
            if (used) byShowtime[1] += count;
            long[] byType = types.computeIfAbsent(typeId, k -> new long[2]);
            typeNames.put(typeId, (String) row[2]);
            byType[0] += count;
            if (used) byType[1] += count;
        }
        List<CheckinSummaryDTO.Group> byShowtime = new ArrayList<>();
        showtimes.forEach((id, v) -> byShowtime.add(new CheckinSummaryDTO.Group(id.isEmpty() ? null : id, null, v[0], v[1])));
        List<CheckinSummaryDTO.Group> byType = new ArrayList<>();
        types.forEach((id, v) -> byType.add(new CheckinSummaryDTO.Group(id, typeNames.get(id), v[0], v[1])));
        byType.sort(Comparator.comparing(g -> g.name() == null ? "" : g.name()));
        return new CheckinSummaryDTO(eventId, total, in, byShowtime, byType, LocalDateTime.now());
    }

    /**
     * Door-staff search by a fragment of the ticket code, the order (booking) id, the seat reference or the ticket type name.
     * The ticket knows its owner only by id, so a name, an email or a phone cannot be searched here. The owner is masked,
     * and the QR hash is returned only for a ticket that can still enter, to a caller who may scan.
     */
    @Transactional(readOnly = true)
    public List<CheckinTicketLookupDTO> search(String eventId, String text, String showtimeId, int limit, boolean maySeeHash) {
        String q = text == null ? "" : text.trim().toLowerCase();
        q = q.replace("%", "").replace("_", "").replace("\\", "");
        if (q.length() < MIN_SEARCH_LENGTH) return List.of();
        int size = Math.min(Math.max(limit, 1), MAX_SEARCH_RESULTS);
        List<Ticket> found = ticketRepository.searchForCheckin(eventId, showtimeParam(showtimeId), "%" + q + "%", DOOR_STATUSES,
                PageRequest.of(0, size));
        List<CheckinTicketLookupDTO> out = new ArrayList<>();
        for (Ticket t : found) {
            TicketInfo info = infoOf(t);
            boolean entered = t.getStatus() == TicketStatus.USED;
            out.add(new CheckinTicketLookupDTO(t.getId(), shortCode(t.getId()), t.getBookingId(), maskOwner(t.getOwnerId()),
                    info.ticketTypeName(), info.seatName(), info.ticketKind(), info.zone(), info.row(), info.seat(), info.showtimeId(),
                    t.getStatus().name(), entered, entered ? t.getUsedAt() : null,
                    maySeeHash && t.getStatus() == TicketStatus.VALID ? t.getQrCodeHash() : null));
        }
        return out;
    }

    private static String showtimeParam(String showtimeId) {
        return showtimeId == null || showtimeId.isBlank() ? "" : showtimeId;
    }

    private static String shortCode(String ticketId) {
        return ticketId == null ? "" : ticketId.split("-")[0].toUpperCase();
    }

    private static String maskOwner(String ownerId) {
        return ownerId == null || ownerId.length() <= 6 ? "***" : ownerId.substring(0, 4) + "***";
    }

    /** Why a ticket cannot enter: a revoked (refunded or cancelled) or an already transferred ticket says so, the rest are "locked". */
    private static String refusalCode(TicketStatus status) {
        if (status == TicketStatus.REVOKED) return "TICKET_REVOKED";
        if (status == TicketStatus.TRANSFERED) return "TICKET_TRANSFERRED";
        return "TICKET_LOCKED";
    }

    private TicketCheckinDTO toListItem(Checkin c) {
        return mapToCheckinDTO(c, infoOf(c.getTicket()), null, c.getStatus() == CheckInStatus.VALID ? "SUCCESS" : c.getStatus().name());
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
                              String showtimeId, LocalDateTime usedAt, String ticketKind, String zone, String row, String seat) {}

    /** The seat as separate parts: kind (SEATED, STANDING, GENERAL), zone, row, seat. A ticket without a seat is GENERAL. */
    private static String[] seatParts(Ticket ticket) {
        if (ticket.getSeat() != null) {
            Seat seat = ticket.getSeat();
            SeatRow row = seat.getSeatRow();
            SeatZone zone = row != null ? row.getSeatZone() : null;
            String zoneName = zone != null ? zone.getName() : ticket.getSeatSection();
            boolean hasRow = row != null && row.getLabel() != null && !row.getLabel().trim().isEmpty() && !row.getLabel().equalsIgnoreCase("STANDING");
            return hasRow ? new String[]{"SEATED", zoneName, row.getLabel(), seat.getSeatNumber()}
                          : new String[]{"STANDING", zoneName, null, seat.getSeatNumber()};
        }
        boolean hasLabels = ticket.getSeatRowLabel() != null && !ticket.getSeatRowLabel().isBlank();
        if (hasLabels || (ticket.getSeatNumberLabel() != null && !ticket.getSeatNumberLabel().isBlank())) {
            return new String[]{hasLabels ? "SEATED" : "STANDING", ticket.getSeatSection(),
                    hasLabels ? ticket.getSeatRowLabel() : null, ticket.getSeatNumberLabel()};
        }
        return new String[]{"GENERAL", null, null, null};
    }

    private TicketInfo infoOf(Ticket ticket) {
        String ticketTypeName = ticket.getTicketType() != null ? ticket.getTicketType().getName() : null;
        String showtimeId = ticket.getShowtimeId() != null ? ticket.getShowtimeId()
                : (ticket.getTicketType() != null ? ticket.getTicketType().getShowtimeId() : null);
        String[] parts = seatParts(ticket);
        return new TicketInfo(ticket.getId(), ticket.getQrCodeHash(), ticket.getOwnerId(), ticketTypeName,
                formatSeatName(ticket), showtimeId, ticket.getUsedAt(), parts[0], parts[1], parts[2], parts[3]);
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
                .ticketKind(ticket.ticketKind()).zone(ticket.zone()).row(ticket.row()).seat(ticket.seat())
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
                .ticketKind(ticket.ticketKind()).zone(ticket.zone()).row(ticket.row()).seat(ticket.seat())
                .build();
    }
}
