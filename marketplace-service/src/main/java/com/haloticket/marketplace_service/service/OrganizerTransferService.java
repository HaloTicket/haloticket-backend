package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.authz.EventAccessClient;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;

/**
 * Nghiệp vụ cho ban tổ chức: xem thống kê, danh sách tin bán lại và lượt tặng của sự kiện, kiểm duyệt hoặc hủy.
 * Mọi thao tác đều kiểm tra quyền qua {@code event-service} và chỉ cho truy cập dữ liệu thuộc đúng sự kiện.
 */
@Service
@RequiredArgsConstructor
public class OrganizerTransferService {
    private final TicketListingRepository listingRepository;
    private final TicketTransferRepository transferRepository;
    private final EventTransferPolicyService policyGate;
    private final TicketListingService ticketListingService;
    private final EventAccessClient eventAccess;

    /**
     * Yêu cầu quyền xem chuyển nhượng ({@code MARKETPLACE_VIEW}) của sự kiện.
     *
     * @param authorization header {@code Authorization} của người gọi
     * @param eventId mã sự kiện
     * @throws ResponseStatusException HTTP 401 khi thiếu token, hoặc lỗi từ kiểm tra quyền khi không đủ quyền
     */
    public void requireOrganizerAccess(String authorization, String eventId) {
        requireOrganizerRole(authorization, eventId, false);
    }

    /**
     * Kiểm tra quyền của người gọi với sự kiện: quyền ghi cần {@code MARKETPLACE_MODERATE}, quyền đọc cần {@code MARKETPLACE_VIEW}.
     *
     * @param authorization header {@code Authorization} của người gọi
     * @param eventId mã sự kiện
     * @param write {@code true} nếu thao tác làm thay đổi dữ liệu
     * @throws ResponseStatusException HTTP 401 khi thiếu token
     */
    private void requireOrganizerRole(String authorization, String eventId, boolean write) {
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        // dùng chung một bảng quyền trong event-service: xem cần MARKETPLACE_VIEW, thay đổi cần MARKETPLACE_MODERATE
        eventAccess.require(eventId, write ? "MARKETPLACE_MODERATE" : "MARKETPLACE_VIEW", authorization);
    }

    /**
     * Duyệt hoặc từ chối tin bán lại của sự kiện sau khi kiểm tra quyền kiểm duyệt.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param actorId mã người thực hiện
     * @param eventId mã sự kiện
     * @param listingId mã tin bán lại
     * @param approved {@code true} để duyệt, {@code false} để từ chối
     * @param reason lý do (có thể bỏ trống)
     * @return trạng thái saga sau khi xử lý
     * @throws ResponseStatusException HTTP 404 {@code LISTING_NOT_FOUND} khi tin không tồn tại hoặc không thuộc sự kiện
     */
    public SagaResponse moderateListing(String authorization, String actorId, String eventId, String listingId, boolean approved, String reason) {
        requireOrganizerRole(authorization, eventId, true);
        requireListingInEvent(eventId, listingId);
        return ticketListingService.moderateListingAsOrganizer(actorId, eventId, listingId, approved, reason);
    }

    /**
     * Hủy tin bán lại của sự kiện sau khi kiểm tra quyền kiểm duyệt.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param actorId mã người thực hiện
     * @param eventId mã sự kiện
     * @param listingId mã tin bán lại
     * @param reason lý do hủy
     * @return trạng thái saga sau khi hủy
     * @throws ResponseStatusException HTTP 404 {@code LISTING_NOT_FOUND} khi tin không tồn tại hoặc không thuộc sự kiện
     */
    public SagaResponse cancelListing(String authorization, String actorId, String eventId, String listingId, String reason) {
        requireOrganizerRole(authorization, eventId, true);
        requireListingInEvent(eventId, listingId);
        return ticketListingService.cancelListingAsOrganizer(actorId, eventId, listingId, reason);
    }

    /**
     * Hủy lượt tặng vé của sự kiện sau khi kiểm tra quyền kiểm duyệt.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param actorId mã người thực hiện
     * @param eventId mã sự kiện
     * @param sagaId mã saga của lượt tặng
     * @param reason lý do hủy
     * @return trạng thái saga sau khi hủy
     * @throws ResponseStatusException HTTP 404 {@code GIFT_NOT_FOUND} khi lượt tặng không tồn tại hoặc không thuộc sự kiện
     */
    public SagaResponse cancelGift(String authorization, String actorId, String eventId, String sagaId, String reason) {
        requireOrganizerRole(authorization, eventId, true);
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        if (!eventId.equals(transfer.getEventId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND");
        return ticketListingService.cancelGiftAsOrganizer(actorId, eventId, sagaId, reason);
    }

    /**
     * Bảo đảm tin bán lại tồn tại và thuộc đúng sự kiện.
     *
     * @param eventId mã sự kiện
     * @param listingId mã tin bán lại
     * @throws ResponseStatusException HTTP 404 {@code LISTING_NOT_FOUND} khi không thỏa
     */
    private void requireListingInEvent(String eventId, String listingId) {
        TicketListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        if (!eventId.equals(listing.getEventId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND");
    }

    /**
     * Tổng hợp số tin bán lại theo trạng thái, số lượt tặng đang chờ hoặc hoàn tất và phiên bản chính sách đã áp dụng của sự kiện.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @return số liệu tổng hợp
     */
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

    /**
     * Liệt kê tin bán lại của sự kiện (có thể lọc theo trạng thái), chuyển sang dạng hiển thị cho ban tổ chức:
     * mã tham chiếu {@code LST-...}, mã chỗ ngồi, email người bán đã che, giá gốc và khoảng giá.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param pageable thông tin phân trang
     * @param status trạng thái cần lọc, có thể {@code null}
     * @return trang tin bán lại
     */
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

    /**
     * Liệt kê các lượt tặng vé của sự kiện (có thể lọc theo trạng thái) với email người tặng và người nhận đã che.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param pageable thông tin phân trang
     * @param status trạng thái cần lọc, có thể {@code null}
     * @return trang lượt tặng
     */
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

    /**
     * Xem chi tiết một tin bán lại của sự kiện.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param listingId mã tin bán lại
     * @return chi tiết tin
     * @throws ResponseStatusException HTTP 404 {@code LISTING_NOT_FOUND} khi tin không tồn tại hoặc không thuộc sự kiện
     */
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

    /**
     * Xem chi tiết một lượt tặng vé của sự kiện.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param sagaId mã saga của lượt tặng
     * @return chi tiết lượt tặng
     * @throws ResponseStatusException HTTP 404 {@code GIFT_NOT_FOUND} khi lượt tặng không tồn tại hoặc không thuộc sự kiện
     */
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

    /**
     * Che bớt email để bảo vệ dữ liệu cá nhân, ví dụ {@code a***z@domain}. Giá trị không phải email thì giữ vài ký tự đầu và cuối;
     * giá trị rỗng hoặc quá ngắn trả {@code ***}.
     *
     * @param val email hoặc mã người dùng
     * @return chuỗi đã che
     */
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

    /**
     * Ghép mã chỗ ngồi từ khu, hàng và ghế, bỏ qua phần trống.
     *
     * @param sec tên khu
     * @param row tên hàng
     * @param seat tên ghế
     * @return chuỗi mã chỗ ngồi, hoặc {@code null} nếu cả ba đều trống
     */
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
