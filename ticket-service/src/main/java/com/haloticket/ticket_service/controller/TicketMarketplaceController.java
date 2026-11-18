package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.dto.response.OwnedTicketResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.haloticket.ticket_service.service.TicketService;

import org.springframework.transaction.annotation.Transactional;

/** API vé của người dùng: danh sách vé sở hữu, chi tiết vé và điều kiện bán lại. Người dùng xác định qua JWT. */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketMarketplaceController {
    private final TicketRepository ticketRepository;
    private final TicketService ticketService;

    /** Liệt kê vé của người dùng hiện tại theo từng trang; thiếu JWT thì trả 401. */
    @GetMapping("/mine")
    @Transactional(readOnly = true)
    public Page<OwnedTicketResponse> myTickets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return ticketService.getMyTickets(jwt.getSubject(), PageRequest.of(page, size));
    }

    /** Lấy chi tiết một vé của người dùng hiện tại; vé của người khác được trả 404 như vé không tồn tại. */
    @GetMapping("/{ticketId}")
    @Transactional(readOnly = true)
    public OwnedTicketResponse myTicket(@PathVariable String ticketId, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // vé của người khác trông giống vé không tồn tại
        if (!jwt.getSubject().equals(ticket.getOwnerId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return OwnedTicketResponse.from(ticket);
    }

    /**
     * Cho biết vé có thể bán lại hay không. Chỉ chủ vé mới xem được (khác chủ trả 403); vé đủ điều kiện khi đang {@code VALID}.
     * Giá gốc lấy từ vé, nếu thiếu thì lấy giá loại vé làm tròn.
     */
    @GetMapping("/{ticketId}/resale-eligibility")
    @Transactional(readOnly = true)
    public ResaleEligibilityResponse resaleEligibility(@PathVariable String ticketId, @AuthenticationPrincipal Jwt jwt) {
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String subject = jwt == null ? null : jwt.getSubject();
        if (subject == null || !subject.equals(ticket.getOwnerId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        boolean eligible = ticket.getStatus() == TicketStatus.VALID;
        BigDecimal originalPrice = ticket.getOriginalPrice();
        if (originalPrice == null && ticket.getTicketType() != null && ticket.getTicketType().getPrice() != null) {
            originalPrice = BigDecimal.valueOf(ticket.getTicketType().getPrice()).setScale(0, java.math.RoundingMode.HALF_UP);
        }
        String ticketTypeName = ticket.getTicketType() == null ? null : ticket.getTicketType().getName();
        String section = ticket.getSeat() == null || ticket.getSeat().getSeatRow() == null
                || ticket.getSeat().getSeatRow().getSeatZone() == null ? null : ticket.getSeat().getSeatRow().getSeatZone().getName();
        String row = ticket.getSeat() == null || ticket.getSeat().getSeatRow() == null ? null : ticket.getSeat().getSeatRow().getLabel();
        String seat = ticket.getSeat() == null ? null : ticket.getSeat().getSeatNumber();
        return new ResaleEligibilityResponse(ticket.getId(), ticket.getEventId(), eligible,
                eligible ? null : "TICKET_NOT_ELIGIBLE", originalPrice, null, null, null, null, null,
                ticketTypeName, section, row, seat,
                ticket.getTicketType() == null ? null : ticket.getTicketType().getId(),
                ticket.getShowtimeId() != null ? ticket.getShowtimeId()
                        : ticket.getTicketType() == null ? null : ticket.getTicketType().getShowtimeId(),
                ticket.getGiftTransferCount());
    }

    /** Kết quả kiểm tra điều kiện bán lại: cờ đủ điều kiện, mã lý do, giá gốc và thông tin vé, loại vé, ghế, số lần tặng. */
    public record ResaleEligibilityResponse(String ticketId, String eventId, boolean eligible, String reasonCode,
                                             BigDecimal originalPrice, BigDecimal minPrice, BigDecimal maxPrice,
                                             Long policyVersion, Instant closesAt, String termsVersion,
                                             String ticketTypeName, String section, String row, String seat,
                                             String ticketTypeId, String showtimeId, int giftTransferCount) {
        /** Constructor rút gọn không có {@code giftTransferCount}, đặt số lần tặng vé bằng 0. */
        public ResaleEligibilityResponse(String ticketId, String eventId, boolean eligible, String reasonCode,
                BigDecimal originalPrice, BigDecimal minPrice, BigDecimal maxPrice, Long policyVersion,
                Instant closesAt, String termsVersion, String ticketTypeName, String section, String row,
                String seat, String ticketTypeId, String showtimeId) {
            this(ticketId, eventId, eligible, reasonCode, originalPrice, minPrice, maxPrice, policyVersion,
                    closesAt, termsVersion, ticketTypeName, section, row, seat, ticketTypeId, showtimeId, 0);
        }
    }
}
