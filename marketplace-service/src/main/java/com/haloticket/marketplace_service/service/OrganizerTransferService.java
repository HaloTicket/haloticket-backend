package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.dto.OrganizerGiftResponse;
import com.haloticket.marketplace_service.dto.OrganizerListingResponse;
import com.haloticket.marketplace_service.dto.OrganizerTransferSummaryResponse;
import com.haloticket.marketplace_service.dto.SagaResponse;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.entity.TicketTransfer;
import com.haloticket.marketplace_service.entity.TicketMemberSnapshot;
import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.enums.TransferStatus;
import com.haloticket.marketplace_service.repository.TicketListingRepository;
import com.haloticket.marketplace_service.repository.TicketTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizerTransferService {
    private final EventServiceClient eventServiceClient;
    private final TicketListingRepository listingRepository;
    private final TicketTransferRepository transferRepository;
    private final EventTransferPolicyService policyGate;
    private final TicketListingService ticketListingService;

    public void requireOrganizerAccess(String authorization, String eventId) {
        requireOrganizerRole(authorization, eventId, false);
    }

    private void requireOrganizerRole(String authorization, String eventId, boolean write) {
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            String role = eventServiceClient.getMyRole(authorization, eventId);
            if (role != null) {
                role = role.replace("\"", "").trim().toUpperCase();
            }
            log.info("requireOrganizerAccess for event {}: normalized role='{}'", eventId, role);
            if (role == null || (!"OWNER".equals(role) && !"ADMIN".equals(role) && (write || !"MANAGER".equals(role)))) {
                log.warn("Access denied for event {}: role '{}' cannot perform organizer {}", eventId, role, write ? "changes" : "reads");
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to check organizer role for event {}: {}", eventId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }
    }

    public SagaResponse moderateListing(String authorization, String actorId, String eventId, String listingId, boolean approved, String reason) {
        requireOrganizerRole(authorization, eventId, true);
        requireListingInEvent(eventId, listingId);
        return ticketListingService.moderateListingAsOrganizer(actorId, eventId, listingId, approved, reason);
    }

    public SagaResponse cancelListing(String authorization, String actorId, String eventId, String listingId, String reason) {
        requireOrganizerRole(authorization, eventId, true);
        requireListingInEvent(eventId, listingId);
        return ticketListingService.cancelListingAsOrganizer(actorId, eventId, listingId, reason);
    }

    public SagaResponse cancelGift(String authorization, String actorId, String eventId, String sagaId, String reason) {
        requireOrganizerRole(authorization, eventId, true);
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        if (!eventId.equals(transfer.getEventId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND");
        return ticketListingService.cancelGiftAsOrganizer(actorId, eventId, sagaId, reason);
    }

    private void requireListingInEvent(String eventId, String listingId) {
        TicketListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        if (!eventId.equals(listing.getEventId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND");
    }

    @Transactional(readOnly = true)
    public OrganizerTransferSummaryResponse getSummary(String authorization, String eventId) {
        requireOrganizerAccess(authorization, eventId);
        long active = listingRepository.countByEventIdAndStatus(eventId, ListingStatus.ACTIVE);
        long reserved = listingRepository.countByEventIdAndStatus(eventId, ListingStatus.RESERVED);
        long completed = listingRepository.countByEventIdAndStatus(eventId, ListingStatus.SOLD);
        long paused = listingRepository.countByEventIdAndStatus(eventId, ListingStatus.PAUSED_BY_EVENT);
        long pendingGifts = transferRepository.countByEventIdAndStatus(eventId, TransferStatus.PENDING);
        long completedGifts = transferRepository.countByEventIdAndStatus(eventId, TransferStatus.COMPLETED);

        Long resaleVer = null;
        Long giftVer = null;
        var policyState = policyGate.getState(eventId);
        if (policyState.isPresent()) {
            resaleVer = policyState.get().getResaleVersion();
            giftVer = policyState.get().getGiftVersion();
        }

        return new OrganizerTransferSummaryResponse(
                active, reserved, completed, paused, pendingGifts, completedGifts, resaleVer, giftVer
        );
    }

    @Transactional(readOnly = true)
    public Page<OrganizerListingResponse> getListings(String authorization, String eventId, Pageable pageable, ListingStatus status) {
        requireOrganizerAccess(authorization, eventId);
        Page<TicketListing> page = (status != null)
                ? listingRepository.findByEventIdAndStatus(eventId, status, pageable)
                : listingRepository.findByEventId(eventId, pageable);

        return page.map(l -> {
            String refCode = "LST-" + (l.getId().length() >= 8 ? l.getId().substring(0, 8).toUpperCase() : l.getId().toUpperCase());
            String seatCode = buildSeatCode(l.getSectionSnapshot(), l.getRowSnapshot(), l.getSeatSnapshot());
            String maskedEmail = maskEmail(l.getSellerId());
            BigDecimal originalPrice = l.getOriginalPrice();
            if (originalPrice == null && !l.remainingMembers().isEmpty()) {
                originalPrice = l.remainingMembers().get(0).getOriginalPrice();
            }
            return new OrganizerListingResponse(
                    l.getId(),
                    refCode,
                    l.getTicketTypeNameSnapshot() != null ? l.getTicketTypeNameSnapshot() : "Vé sự kiện",
                    seatCode,
                    maskedEmail,
                    l.getPrice(),
                    "VND",
                    l.getStatus().name(),
                    l.getCreatedAt(),
                    l.remainingMembers().size(),
                    l.remainingMembers().stream().map(TicketMemberSnapshot::getTicketId).toList(),
                    originalPrice,
                    l.getMinPriceSnapshot(),
                    l.getMaxPriceSnapshot(),
                    l.getServiceFeePercent() == null ? new BigDecimal("5.00") : l.getServiceFeePercent(),
                    l.getModerationReason()
            );
        });
    }

    @Transactional(readOnly = true)
    public Page<OrganizerGiftResponse> getGifts(String authorization, String eventId, Pageable pageable, TransferStatus status) {
        requireOrganizerAccess(authorization, eventId);
        Page<TicketTransfer> page = (status != null)
                ? transferRepository.findByEventIdAndStatus(eventId, status, pageable)
                : transferRepository.findByEventId(eventId, pageable);

        return page.map(t -> {
            String senderMasked = maskEmail(t.getFromUserId());
            String recipientMasked = maskEmail(t.getRecipientEmail() != null ? t.getRecipientEmail() : t.getToUserId());
            return new OrganizerGiftResponse(
                    t.getSagaId(),
                    senderMasked,
                    recipientMasked,
                    t.getTicketTypeName() != null ? t.getTicketTypeName() : "Vé sự kiện",
                    t.getCreatedAt(),
                    t.getInvitationExpiresAt() != null ? t.getInvitationExpiresAt() : t.getCreatedAt(),
                    t.getStatus().name(),
                    t.getMembers().isEmpty() ? 1 : t.getMembers().size(),
                    t.getMembers().isEmpty() ? java.util.List.of(t.getTicketId()) : t.getMembers().stream().map(TicketMemberSnapshot::getTicketId).toList(),
                    t.getTransferredAt(), t.isRequireEkyc(), t.getRecipientEkycVerified()
            );
        });
    }

    @Transactional(readOnly = true)
    public OrganizerListingResponse getListingDetail(String authorization, String eventId, String listingId) {
        requireOrganizerAccess(authorization, eventId);
        TicketListing l = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        if (!eventId.equals(l.getEventId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND");
        }
        String refCode = "LST-" + (l.getId().length() >= 8 ? l.getId().substring(0, 8).toUpperCase() : l.getId().toUpperCase());
        String seatCode = buildSeatCode(l.getSectionSnapshot(), l.getRowSnapshot(), l.getSeatSnapshot());
        BigDecimal originalPrice = l.getOriginalPrice();
        if (originalPrice == null && !l.remainingMembers().isEmpty()) {
            originalPrice = l.remainingMembers().get(0).getOriginalPrice();
        }
        return new OrganizerListingResponse(
                l.getId(),
                refCode,
                l.getTicketTypeNameSnapshot() != null ? l.getTicketTypeNameSnapshot() : "Vé sự kiện",
                seatCode,
                maskEmail(l.getSellerId()),
                l.getPrice(),
                "VND",
                l.getStatus().name(),
                l.getCreatedAt(),
                l.remainingMembers().size(),
                l.remainingMembers().stream().map(TicketMemberSnapshot::getTicketId).toList(),
                originalPrice,
                l.getMinPriceSnapshot(),
                l.getMaxPriceSnapshot(),
                l.getServiceFeePercent() == null ? new BigDecimal("5.00") : l.getServiceFeePercent(),
                l.getModerationReason()
        );
    }

    @Transactional(readOnly = true)
    public OrganizerGiftResponse getGiftDetail(String authorization, String eventId, String sagaId) {
        requireOrganizerAccess(authorization, eventId);
        TicketTransfer t = transferRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        if (!eventId.equals(t.getEventId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND");
        }
        return new OrganizerGiftResponse(
                t.getSagaId(),
                maskEmail(t.getFromUserId()),
                maskEmail(t.getRecipientEmail() != null ? t.getRecipientEmail() : t.getToUserId()),
                t.getTicketTypeName() != null ? t.getTicketTypeName() : "Vé sự kiện",
                t.getCreatedAt(),
                t.getInvitationExpiresAt() != null ? t.getInvitationExpiresAt() : t.getCreatedAt(),
                t.getStatus().name(),
                t.getMembers().isEmpty() ? 1 : t.getMembers().size(),
                t.getMembers().isEmpty() ? java.util.List.of(t.getTicketId()) : t.getMembers().stream().map(TicketMemberSnapshot::getTicketId).toList(),
                t.getTransferredAt(), t.isRequireEkyc(), t.getRecipientEkycVerified()
        );
    }

    public static String maskEmail(String val) {
        if (val == null || val.isBlank()) return "***";
        int at = val.indexOf('@');
        if (at > 1) {
            String name = val.substring(0, at);
            String domain = val.substring(at);
            if (name.length() <= 2) return name.charAt(0) + "***" + domain;
            return name.charAt(0) + "***" + name.charAt(name.length() - 1) + domain;
        }
        if (val.length() > 6) {
            return val.substring(0, 3) + "***" + val.substring(val.length() - 2);
        }
        return "***";
    }

    private String buildSeatCode(String sec, String row, String seat) {
        StringBuilder sb = new StringBuilder();
        if (sec != null && !sec.isBlank()) sb.append(sec);
        if (row != null && !row.isBlank()) {
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append("Hàng ").append(row);
        }
        if (seat != null && !seat.isBlank()) {
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append("Ghế ").append(seat);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
