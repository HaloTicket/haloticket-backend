package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.TicketServiceClient;
import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.client.UserServiceClient;
import com.haloticket.marketplace_service.dto.*;
import com.haloticket.marketplace_service.entity.*;
import com.haloticket.marketplace_service.enums.*;
import com.haloticket.marketplace_service.repository.*;
import lombok.RequiredArgsConstructor;
import feign.FeignException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.time.Duration;

/**
 * Nghiệp vụ chính của chợ vé: kiểm tra điều kiện bán lại, đăng tin bán lại và tặng vé theo mô hình saga
 * (khóa vé, tạo tin hoặc lời mời, chuyển quyền sở hữu, nhả vé khi bù trừ), giữ chỗ khi mua và kiểm duyệt tin.
 * Các thông điệp gửi sang service khác đi qua bảng outbox.
 */
@Service
@RequiredArgsConstructor
public class TicketListingService {
    /** Thời gian chờ thêm sau khi hết giữ chỗ trước khi nhả tin có giao dịch đang xử lý thanh toán (chuyển khoản có thể đến chậm). */
    static final Duration PAYMENT_PROCESSING_GRACE = Duration.ofMinutes(30);

    private final TicketListingRepository listingRepository;
    private final MarketplaceSagaRepository sagaRepository;
    private final OutboxEventRepository outboxRepository;
    private final TicketTransferRepository transferRepository;
    private final MarketplaceTransactionRepository transactionRepository;
    private final TicketServiceClient ticketServiceClient;
    private final EventServiceClient eventServiceClient;
    private final UserServiceClient userServiceClient;
    private final ObjectMapper objectMapper;
    private final EventTransferPolicyService policyGate;

    /**
     * Kiểm tra vé có thể bán lại không và tính khoảng giá cho phép theo chính sách của sự kiện
     * (giá tối thiểu làm tròn lên, tối đa làm tròn xuống, theo phần trăm giá gốc).
     * Trả kết quả không đủ điều kiện với mã lý do khi chưa cấu hình chính sách ({@code RESALE_POLICY_NOT_CONFIGURED}),
     * chính sách tắt, chưa mở, loại vé không thuộc phạm vi hoặc đã quá hạn ({@code RESALE_DEADLINE_PASSED}).
     *
     * @param authorization header {@code Authorization} của người dùng
     * @param ticketId mã vé
     * @return kết quả kiểm tra điều kiện, hoặc {@code null} nếu {@code ticket-service} không trả gì
     */
    public TicketServiceClient.ResaleEligibilityResponse eligibility(String authorization, String ticketId) {
        TicketServiceClient.ResaleEligibilityResponse ticket = ticketServiceClient.getResaleEligibility(authorization, ticketId);
        if (ticket == null || !ticket.eligible()) return ticket;

        EventServiceClient.ResalePolicyResponse policy;
        try {
            policy = eventServiceClient.getResalePolicy(authorization, ticket.eventId());
        } catch (FeignException.NotFound exception) {
            return new TicketServiceClient.ResaleEligibilityResponse(
                    ticket.ticketId(), ticket.eventId(), false, "RESALE_POLICY_NOT_CONFIGURED", ticket.originalPrice(),
                    null, null, null, null, null,
                    ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat(), ticket.ticketTypeId(), ticket.showtimeId());
        }
        boolean policyOpen = policy != null
                && policy.enabled()
                && policy.minPricePercent() != null
                && policy.maxPricePercent() != null
                && (policy.opensAt() == null || !policy.opensAt().isAfter(Instant.now()))
                && (!"SELECTED".equals(policy.ticketTypeScope()) || policy.allowedTicketTypeIds() != null && policy.allowedTicketTypeIds().contains(ticket.ticketTypeId()))
                && (policy.closesAt() == null || !policy.closesAt().isBefore(Instant.now()));
        if (!policyOpen) {
            String reasonCode = policy != null && policy.enabled() && policy.closesAt() != null
                    && policy.closesAt().isBefore(Instant.now()) ? "RESALE_DEADLINE_PASSED" : "RESALE_DISABLED";
            return new TicketServiceClient.ResaleEligibilityResponse(
                    ticket.ticketId(), ticket.eventId(), false, reasonCode, ticket.originalPrice(),
                    null, null, policy == null ? null : policy.version(),
                    policy == null ? null : policy.closesAt(), policy == null ? null : policy.termsVersion(),
                    ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat(), ticket.ticketTypeId(), ticket.showtimeId());
        }

        BigDecimal originalPrice = ticket.originalPrice() == null ? BigDecimal.ZERO : ticket.originalPrice();
        BigDecimal minPrice = originalPrice.multiply(policy.minPricePercent())
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.CEILING);
        BigDecimal maxPrice = originalPrice.multiply(policy.maxPricePercent())
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR);
        return new TicketServiceClient.ResaleEligibilityResponse(
                ticket.ticketId(), ticket.eventId(), true, null, originalPrice,
                minPrice, maxPrice, policy.version(), policy.closesAt(), policy.termsVersion(),
                ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat(), ticket.ticketTypeId(), ticket.showtimeId(),
                ticket.giftTransferCount(), policy.serviceFeePercent() == null ? new BigDecimal("5") : policy.serviceFeePercent());
    }

    /**
     * Bắt đầu saga đăng bán lại một hoặc nhiều vé. Yêu cầu khóa idempotency; cùng khóa với nội dung giống thì trả lại saga cũ.
     * Kiểm tra giá nguyên không âm, eKYC của người bán, từng vé đủ điều kiện và cùng suất diễn, chính sách còn mở,
     * giá nằm trong khoảng cho phép và phiên bản chính sách không đổi; sau đó lưu saga ở bước chờ khóa vé và đưa lệnh khóa vào outbox.
     *
     * @param actorId mã người bán
     * @param authorization header {@code Authorization} của người bán
     * @param idempotencyKey khóa idempotency bắt buộc
     * @param request thông tin tin bán
     * @return trạng thái saga vừa tạo hoặc đã có
     * @throws ResponseStatusException khi thiếu khóa (400), giá hoặc danh sách vé không hợp lệ (422), khóa dùng lại với nội dung khác,
     *         thiếu eKYC, vé không đủ điều kiện hoặc chính sách thay đổi (409), dịch vụ phụ thuộc không khả dụng (503)
     */
    @Transactional
    public SagaResponse startResale(String actorId, String authorization, String idempotencyKey, CreateListingRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        if (request == null || request.price() == null || request.price().scale() > 0 || request.price().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_INVALID");
        }
        List<String> ticketIds = selectedIds(request.ticketId(), request.ticketIds());
        String hash = hash(request);
        Optional<MarketplaceSaga> existing = sagaRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different request");
            return toResponse(existing.get());
        }

        UserServiceClient.ResaleSellerEligibility sellerEligibility;
        try { sellerEligibility = userServiceClient.getResaleEligibility(authorization); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_SERVICE_UNAVAILABLE", exception); }
        if (sellerEligibility == null || !sellerEligibility.eligible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EKYC_REQUIRED");
        }

        List<TicketServiceClient.ResaleEligibilityResponse> eligibleTickets = new ArrayList<>();
        for (String ticketId : ticketIds) {
            TicketServiceClient.ResaleEligibilityResponse item;
            try { item = eligibility(authorization, ticketId); }
            catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ELIGIBILITY_SERVICE_UNAVAILABLE", exception); }
            if (item == null || !item.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
            if (!eligibleTickets.isEmpty() && (!Objects.equals(eligibleTickets.get(0).eventId(), item.eventId())
                    || !Objects.equals(eligibleTickets.get(0).showtimeId(), item.showtimeId())))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TICKETS_MUST_SHARE_SHOWTIME");
            if (item.eventId() != null) policyGate.requireResaleEnabled(item.eventId());
            if (request.price().compareTo(item.minPrice()) < 0 || request.price().compareTo(item.maxPrice()) > 0)
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_OUT_OF_RANGE");
            if (request.policyVersion() != null && item.policyVersion() != null && !request.policyVersion().equals(item.policyVersion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "POLICY_CHANGED");
            eligibleTickets.add(item);
        }
        TicketServiceClient.ResaleEligibilityResponse eligibility = eligibleTickets.get(0);
        var resalePolicy = eventServiceClient.getResalePolicy(authorization, eligibility.eventId());
        if (resalePolicy == null || !Objects.equals(resalePolicy.version(), eligibility.policyVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "POLICY_CHANGED");
        BigDecimal minPrice = eligibility.minPrice();
        BigDecimal maxPrice = eligibility.maxPrice();
        Instant now = Instant.now();
        String sagaId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sagaId", sagaId); payload.put("ticketId", ticketIds.get(0)); payload.put("ticketIds", ticketIds); payload.put("sellerId", actorId);
        payload.put("price", request.price()); payload.put("eventId", eligibility.eventId()); payload.put("originalPrice", eligibility.originalPrice());
        payload.put("minPrice", minPrice); payload.put("maxPrice", maxPrice); payload.put("policyVersion", eligibility.policyVersion());
        payload.put("closesAt", eligibility.closesAt());
        payload.put("serviceFeePercent", eligibility.serviceFeePercent() == null ? new BigDecimal("5") : eligibility.serviceFeePercent());
        payload.put("requireApproval", resalePolicy.requireApproval());
        payload.put("termsVersion", request.termsVersion() != null ? request.termsVersion() : eligibility.termsVersion());
        payload.put("ticketTypeName", eligibility.ticketTypeName()); payload.put("section", eligibility.section());
        payload.put("row", eligibility.row()); payload.put("seat", eligibility.seat());
        payload.put("tickets", eligibleTickets.stream().map(item -> snapshot(item)).toList());
        int quantity = ticketIds.size();
        payload.put("quantity", quantity);
        payload.put("allowRetail", retailAllowed(quantity, request.allowRetail()));
        try {
            MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("RESALE_LISTING").ticketId(ticketIds.get(0)).actorId(actorId)
                    .state(SagaState.WAITING_LOCK).step("LOCK_REQUESTED").payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey(idempotencyKey).requestHash(hash).deadlineAt(eligibility.closesAt()).nextRetryAt(now).retryCount(0).build();
            sagaRepository.save(saga);
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_LOCK_REQUESTED")
                    .topic("haloticket.ticket.lock.requested.v1").payload(objectMapper.writeValueAsString(Map.of("messageId", UUID.randomUUID().toString(), "sagaId", sagaId, "ticketId", ticketIds.get(0), "ticketIds", ticketIds, "sellerId", actorId, "purpose", "RESALE")))
                    .status(OutboxStatus.PENDING).retryCount(0).build());
            return toResponse(saga);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create resale saga", exception); }
    }

    /**
     * Xem trạng thái một saga của chính người khởi tạo.
     *
     * @param actorId mã người gọi
     * @param sagaId mã saga
     * @return trạng thái saga
     * @throws ResponseStatusException HTTP 404 nếu không có saga, HTTP 403 nếu saga không thuộc người gọi
     */
    @Transactional(readOnly = true)
    public SagaResponse getSaga(String actorId, String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!saga.getActorId().equals(actorId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return toResponse(saga);
    }

    /**
     * Liệt kê tin bán lại công khai khi không biết người dùng hiện tại.
     *
     * @param pageable thông tin phân trang
     * @return trang tin đang mở
     */
    @Transactional(readOnly = true)
    public Page<ListingResponse> publicListings(Pageable pageable) {
        return publicListings(null, pageable);
    }

    /**
     * Liệt kê tin bán lại đang mở (đã công bố, chưa hết hạn), đánh dấu tin do người dùng hiện tại sở hữu.
     *
     * @param currentUserId mã người dùng hiện tại, có thể {@code null}
     * @param pageable thông tin phân trang
     * @return trang tin đang mở
     */
    @Transactional(readOnly = true)
    public Page<ListingResponse> publicListings(String currentUserId, Pageable pageable) {
        return listingRepository.findPublicActive(ListingStatus.ACTIVE, Instant.now(), pageable)
                .map(listing -> ListingResponse.from(listing, currentUserId));
    }

    /**
     * Liệt kê tin bán lại công khai của một sự kiện khi không biết người dùng hiện tại.
     *
     * @param eventId mã sự kiện
     * @param pageable thông tin phân trang
     * @return trang tin đang mở
     */
    @Transactional(readOnly = true)
    public Page<ListingResponse> publicListingsByEvent(String eventId, Pageable pageable) {
        return publicListingsByEvent(eventId, null, pageable);
    }

    /**
     * Liệt kê tin bán lại đang mở của một sự kiện, đánh dấu tin do người dùng hiện tại sở hữu.
     *
     * @param eventId mã sự kiện
     * @param currentUserId mã người dùng hiện tại, có thể {@code null}
     * @param pageable thông tin phân trang
     * @return trang tin đang mở
     */
    @Transactional(readOnly = true)
    public Page<ListingResponse> publicListingsByEvent(String eventId, String currentUserId, Pageable pageable) {
        return listingRepository.findPublicActiveByEventId(eventId, ListingStatus.ACTIVE, Instant.now(), pageable)
                .map(listing -> ListingResponse.from(listing, currentUserId));
    }

    /**
     * Tổng hợp các sự kiện có tin bán lại đang mở, kèm số tin và giá thấp nhất.
     *
     * @return danh sách tóm tắt sự kiện
     */
    @Transactional(readOnly = true)
    public List<ResaleEventSummary> publicEvents() {
        return listingRepository.summarizePublicEvents(ListingStatus.ACTIVE, Instant.now());
    }

    /**
     * Xem công khai một tin bán lại khi không biết người dùng hiện tại.
     *
     * @param listingId mã tin
     * @return chi tiết tin
     */
    @Transactional(readOnly = true)
    public ListingResponse publicListing(String listingId) {
        return publicListing(listingId, null);
    }

    /**
     * Xem công khai một tin bán lại đang mở.
     *
     * @param listingId mã tin
     * @param currentUserId mã người dùng hiện tại, có thể {@code null}
     * @return chi tiết tin
     * @throws ResponseStatusException HTTP 404 {@code LISTING_NOT_AVAILABLE} khi tin không tồn tại, chưa mở, đã hết hạn hoặc không còn ở trạng thái {@code ACTIVE}
     */
    @Transactional(readOnly = true)
    public ListingResponse publicListing(String listingId, String currentUserId) {
        TicketListing listing = listingRepository.findById(listingId)
                .filter(item -> item.getStatus() == ListingStatus.ACTIVE && item.getPublishedAt() != null
                        && (item.getExpiresAt() == null || item.getExpiresAt().isAfter(Instant.now())))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_AVAILABLE"));
        return ListingResponse.from(listing, currentUserId);
    }

    /**
     * Giữ chỗ toàn bộ vé còn lại của một tin bán lại.
     *
     * @param buyerId mã người mua
     * @param idempotencyKey khóa idempotency
     * @param listingId mã tin
     * @return thông tin giữ chỗ
     */
    @Transactional
    public ReservationResponse reserveListing(String buyerId, String idempotencyKey, String listingId) {
        return reserveListing(buyerId, idempotencyKey, listingId, null);
    }

    /**
     * Giữ chỗ vé của một tin bán lại trong 10 phút và tạo giao dịch chờ thanh toán. Khóa idempotency cũng là mã tham chiếu thanh toán,
     * nên gọi lặp với cùng khóa và cùng lựa chọn sẽ trả lại giao dịch cũ. Người mua đang giữ chỗ hợp lệ cũng nhận lại giao dịch hiện có.
     * Chỉ cho chọn một phần vé khi tin cho phép bán lẻ.
     *
     * @param buyerId mã người mua
     * @param idempotencyKey khóa idempotency bắt buộc
     * @param listingId mã tin
     * @param requestedIds các vé muốn mua, hoặc {@code null} để mua tất cả vé còn lại
     * @return thông tin giữ chỗ
     * @throws ResponseStatusException HTTP 400 khi thiếu khóa, HTTP 404 khi không có tin, HTTP 409 khi khóa dùng lại với nội dung khác,
     *         tin không còn bán, người mua là người bán hoặc lựa chọn vé không hợp lệ
     */
    @Transactional
    public ReservationResponse reserveListing(String buyerId, String idempotencyKey, String listingId, List<String> requestedIds) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
        Optional<MarketplaceTransaction> existing = transactionRepository.findByPaymentReference(idempotencyKey);
        if (existing.isPresent()) {
            MarketplaceTransaction transaction = existing.get();
            if (!transaction.getBuyerId().equals(buyerId) || !transaction.getTicketListing().getId().equals(listingId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different request");
            }
            if ((requestedIds == null && Boolean.TRUE.equals(transaction.getSelectionExplicit()))
                    || (requestedIds != null && !new LinkedHashSet<>(selectedIds(null, requestedIds)).equals(transaction.getTicketIds())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different selection");
            return reservationResponse(transaction);
        }

        TicketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        Instant now = Instant.now();
        if (listing.getStatus() == ListingStatus.RESERVED && buyerId.equals(listing.getReservedBy())
                && listing.getReservedUntil() != null && listing.getReservedUntil().isAfter(now)) {
            MarketplaceTransaction transaction = transactionRepository.findFirstByTicketListing_IdAndBuyerIdAndStatusIn(
                    listingId, buyerId, List.of(MarketplaceTransactionStatus.PENDING_PAYMENT,
                            MarketplaceTransactionStatus.PAYMENT_PROCESSING))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_TRANSACTION_NOT_FOUND"));
            if ((requestedIds == null && Boolean.TRUE.equals(transaction.getSelectionExplicit()))
                    || (requestedIds != null && !new LinkedHashSet<>(selectedIds(null, requestedIds)).equals(transaction.getTicketIds())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_SELECTION_NOT_AVAILABLE");
            return reservationResponse(transaction);
        }
        if (listing.getStatus() != ListingStatus.ACTIVE || listing.getPublishedAt() == null
                || (listing.getExpiresAt() != null && !listing.getExpiresAt().isAfter(now))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_NOT_AVAILABLE");
        }
        if (listing.getEventId() != null) {
            policyGate.requireResaleEnabled(listing.getEventId());
        }
        if (listing.getSellerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BUYER_IS_SELLER");
        }
        List<String> remaining = listing.remainingMembers().stream().map(TicketMemberSnapshot::getTicketId).toList();
        List<String> selected = requestedIds == null ? remaining : selectedIds(null, requestedIds);
        if (!remaining.containsAll(selected) || (!Boolean.TRUE.equals(listing.getAllowRetail()) && selected.size() != remaining.size()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_SELECTION_NOT_AVAILABLE");

        Instant reservationExpiresAt = now.plusSeconds(10 * 60);
        listing.setStatus(ListingStatus.RESERVED);
        listing.setReservedBy(buyerId);
        listing.setReservedUntil(reservationExpiresAt);
        listingRepository.save(listing);
        MarketplaceTransaction transaction = MarketplaceTransaction.builder()
                .ticketListing(listing).buyerId(buyerId).sellerId(listing.getSellerId())
                .amount(listing.getPrice().multiply(BigDecimal.valueOf(selected.size())))
                .serviceFeePercent(listing.getServiceFeePercent() == null ? new BigDecimal("5") : listing.getServiceFeePercent())
                .ticketIds(new LinkedHashSet<>(selected))
                .selectionExplicit(requestedIds != null)
                .currency("VND").status(MarketplaceTransactionStatus.PENDING_PAYMENT)
                .paymentReference(idempotencyKey).reservationExpiresAt(reservationExpiresAt).build();
        return reservationResponse(transactionRepository.save(transaction));
    }

    /**
     * Xác định trạng thái tin sau khi gỡ giữ chỗ: {@code EXPIRED} nếu đã hết hạn, {@code PAUSED_BY_EVENT} nếu ban tổ chức đã tắt bán lại
     * (đồng thời đặt lý do tạm dừng), ngược lại {@code ACTIVE}.
     *
     * @param listing tin bán lại
     * @param now mốc thời gian hiện tại
     * @return trạng thái mới của tin
     */
    private ListingStatus resolveStatusAfterReservation(TicketListing listing, Instant now) {
        if (listing.getExpiresAt() != null && !listing.getExpiresAt().isAfter(now)) {
            return ListingStatus.EXPIRED;
        }
        if (listing.getEventId() != null) {
            try {
                policyGate.requireResaleEnabled(listing.getEventId());
            } catch (Exception e) {
                listing.setPauseReason("ORGANIZER_DISABLED_RESALE");
                return ListingStatus.PAUSED_BY_EVENT;
            }
        }
        listing.setPauseReason(null);
        return ListingStatus.ACTIVE;
    }

    /**
     * Giải phóng các giữ chỗ quá hạn: đánh dấu giao dịch chờ thanh toán là {@code EXPIRED} và đưa tin về trạng thái bán phù hợp.
     * Giao dịch đang xử lý thanh toán ({@code PAYMENT_PROCESSING}) chỉ bị hết hạn sau thêm {@link #PAYMENT_PROCESSING_GRACE}.
     */
    @Transactional
    public void expireReservations() {
        Instant now = Instant.now();
        for (TicketListing candidate : listingRepository.findByStatusAndReservedUntilBefore(ListingStatus.RESERVED, now)) {
            TicketListing listing = listingRepository.findByIdForUpdate(candidate.getId()).orElse(null);
            if (listing == null || listing.getStatus() != ListingStatus.RESERVED || listing.getReservedUntil() == null
                    || !listing.getReservedUntil().isBefore(now)) continue;
            MarketplaceTransaction transaction = transactionRepository.findByTicketListing_IdAndStatus(listing.getId(), MarketplaceTransactionStatus.PENDING_PAYMENT).orElse(null);
            if (transaction == null) {
                // giao dịch đã sang PAYMENT_PROCESSING (người mua đã mở thanh toán) mà không bao giờ trả tiền hay báo lỗi, ví dụ bỏ dở mã
                // QR chuyển khoản: nếu không hết hạn thì tin kẹt ở RESERVED mãi. Chỉ nhả sau thời gian ân hạn để một khoản chuyển khoản
                // đến chậm vẫn kịp được ghi nhận.
                transaction = transactionRepository.findByTicketListing_IdAndStatus(listing.getId(), MarketplaceTransactionStatus.PAYMENT_PROCESSING)
                        .filter(candidateTx -> candidateTx.getReservationExpiresAt() != null
                                && candidateTx.getReservationExpiresAt().plus(PAYMENT_PROCESSING_GRACE).isBefore(now))
                        .orElse(null);
            }
            if (transaction == null) continue;
            transaction.setStatus(MarketplaceTransactionStatus.EXPIRED);
            transaction.setCancelledAt(now);
            transactionRepository.save(transaction);
            listing.setStatus(resolveStatusAfterReservation(listing, now));
            listing.setReservedBy(null);
            listing.setReservedUntil(null);
            listingRepository.save(listing);
        }
    }

    /**
     * Hủy giữ chỗ của người mua: đánh dấu giao dịch chờ thanh toán là {@code CANCELLED} và mở lại tin. Bỏ qua nếu tin không ở trạng thái
     * giữ chỗ hoặc người gọi không phải người đang giữ.
     *
     * @param buyerId mã người mua, hoặc {@code null} để bỏ qua kiểm tra người giữ
     * @param listingId mã tin
     */
    @Transactional
    public void cancelReservation(String buyerId, String listingId) {
        TicketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElse(null);
        if (listing != null && listing.getStatus() == ListingStatus.RESERVED) {
            if (buyerId != null && !buyerId.equals(listing.getReservedBy())) {
                return;
            }
            Instant now = Instant.now();
            transactionRepository.findByTicketListing_IdAndStatus(listing.getId(), MarketplaceTransactionStatus.PENDING_PAYMENT)
                    .ifPresent(transaction -> {
                        transaction.setStatus(MarketplaceTransactionStatus.CANCELLED);
                        transaction.setCancelledAt(now);
                        transactionRepository.save(transaction);
                    });
            listing.setStatus(resolveStatusAfterReservation(listing, now));
            listing.setReservedBy(null);
            listing.setReservedUntil(null);
            listingRepository.save(listing);
        }
    }

    /**
     * Liệt kê các tin bán lại của người bán.
     *
     * @param actorId mã người bán
     * @param pageable thông tin phân trang
     * @return trang tin của người bán
     */
    @Transactional(readOnly = true)
    public Page<ListingResponse> myListings(String actorId, Pageable pageable) { return listingRepository.findBySellerId(actorId, pageable).map(ListingResponse::from); }

    /**
     * Cho phép bán lẻ khi tin chỉ có một vé, hoặc khi người bán không tắt bán lẻ một cách tường minh.
     *
     * @param quantity số vé trong tin
     * @param requested lựa chọn của người bán, có thể {@code null}
     * @return {@code true} nếu cho phép bán lẻ
     */
    static boolean retailAllowed(int quantity, Boolean requested) {
        return quantity <= 1 || !Boolean.FALSE.equals(requested);
    }

    /**
     * Người bán hủy tin của mình.
     *
     * @param actorId mã người gọi
     * @param listingId mã tin
     * @return trạng thái saga bù trừ
     * @throws ResponseStatusException HTTP 404 nếu không có tin, HTTP 403 {@code LISTING_NOT_OWNED} nếu không phải chủ tin
     */
    @Transactional
    public SagaResponse cancelListing(String actorId, String listingId) {
        TicketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        if (!listing.getSellerId().equals(actorId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "LISTING_NOT_OWNED");
        return cancelListingLocked(actorId, listing, "SELLER_CANCELLED");
    }

    /**
     * Ban tổ chức hủy tin của sự kiện; lý do mặc định là {@code ORGANIZER_CANCELLED}.
     *
     * @param actorId mã người thực hiện
     * @param eventId mã sự kiện
     * @param listingId mã tin
     * @param reason lý do hủy, có thể bỏ trống
     * @return trạng thái saga bù trừ
     */
    @Transactional
    public SagaResponse cancelListingAsOrganizer(String actorId, String eventId, String listingId, String reason) {
        TicketListing listing = organizerListing(eventId, listingId);
        return cancelListingLocked(actorId, listing, reason == null || reason.isBlank() ? "ORGANIZER_CANCELLED" : boundedReason(reason));
    }

    /**
     * Ban tổ chức duyệt hoặc từ chối tin đang chờ duyệt. Duyệt thì mở bán và phát sự kiện kích hoạt; từ chối thì bắt buộc có lý do và hủy tin.
     * Gọi lặp với kết quả đã đạt được thì trả lại saga hiện tại.
     *
     * @param actorId mã người thực hiện
     * @param eventId mã sự kiện
     * @param listingId mã tin
     * @param approved {@code true} để duyệt, {@code false} để từ chối
     * @param reason lý do (bắt buộc khi từ chối)
     * @return trạng thái saga
     * @throws ResponseStatusException HTTP 409 khi tin không chờ duyệt hoặc đã hết hạn, HTTP 422 khi thiếu lý do từ chối
     */
    @Transactional
    public SagaResponse moderateListingAsOrganizer(String actorId, String eventId, String listingId, boolean approved, String reason) {
        TicketListing listing = organizerListing(eventId, listingId);
        if (approved && listing.getStatus() == ListingStatus.ACTIVE) return toResponse(listingSaga(listing));
        if (!approved && listing.getStatus() == ListingStatus.CANCELLED) return toResponse(listingSaga(listing));
        if (listing.getStatus() != ListingStatus.PENDING_APPROVAL)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_NOT_PENDING_APPROVAL");
        if (!approved) {
            if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "REJECTION_REASON_REQUIRED");
            return cancelListingLocked(actorId, listing, boundedReason(reason));
        }
        if (listing.getExpiresAt() != null && !listing.getExpiresAt().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_EXPIRED");
        policyGate.requireResaleEnabled(eventId);
        listing.setStatus(ListingStatus.ACTIVE); listing.setPublishedAt(Instant.now());
        listing.setModeratedBy(actorId); listing.setModeratedAt(Instant.now()); listing.setModerationReason(null);
        listingRepository.save(listing);
        var saga = listingSaga(listing);
        saga.setStep("LISTING_ACTIVE"); sagaRepository.save(saga);
        outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_LISTING").aggregateId(listingId)
                .eventType("RESALE_LISTING_ACTIVATED").topic("haloticket.marketplace.listing.activated.v1")
                .payload(saga.getPayload()).status(OutboxStatus.PENDING).retryCount(0).build());
        return toResponse(saga);
    }

    /**
     * Lấy tin kèm khóa ghi và bảo đảm nó thuộc đúng sự kiện.
     *
     * @param eventId mã sự kiện
     * @param listingId mã tin
     * @return tin bán lại
     * @throws ResponseStatusException HTTP 404 nếu không có tin, HTTP 403 {@code EVENT_MISMATCH} nếu tin thuộc sự kiện khác
     */
    private TicketListing organizerListing(String eventId, String listingId) {
        TicketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        if (!Objects.equals(eventId, listing.getEventId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EVENT_MISMATCH");
        return listing;
    }

    /**
     * Lấy saga đã tạo ra tin bán lại.
     *
     * @param listing tin bán lại
     * @return saga của tin
     * @throws ResponseStatusException HTTP 409 {@code LISTING_SAGA_NOT_FOUND} nếu không tìm thấy saga
     */
    private MarketplaceSaga listingSaga(TicketListing listing) {
        return sagaRepository.findById(listing.getSagaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_SAGA_NOT_FOUND"));
    }

    /**
     * Cắt khoảng trắng đầu cuối của lý do và kiểm tra độ dài tối đa 1000 ký tự.
     *
     * @param reason lý do
     * @return lý do đã cắt khoảng trắng
     * @throws ResponseStatusException HTTP 422 {@code REASON_TOO_LONG} khi quá dài
     */
    private static String boundedReason(String reason) {
        if (reason.trim().length() > 1000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_TOO_LONG");
        return reason.trim();
    }

    /**
     * Hủy tin đã được khóa: đặt trạng thái {@code CANCELLED}, chuyển saga sang bù trừ và đưa lệnh nhả vé vào outbox.
     * Trả lại saga hiện tại nếu tin đã hủy.
     *
     * @param actorId mã người thực hiện
     * @param listing tin cần hủy
     * @param reason lý do hủy
     * @return trạng thái saga
     * @throws ResponseStatusException HTTP 409 {@code LISTING_NOT_CANCELLABLE} khi tin không ở trạng thái hủy được
     */
    private SagaResponse cancelListingLocked(String actorId, TicketListing listing, String reason) {
        MarketplaceSaga saga = listingSaga(listing);
        if (listing.getStatus() == ListingStatus.CANCELLED) return toResponse(saga);
        if (listing.getStatus() != ListingStatus.ACTIVE && listing.getStatus() != ListingStatus.PAUSED_BY_EVENT && listing.getStatus() != ListingStatus.PENDING_APPROVAL)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_NOT_CANCELLABLE");
        String listingId = listing.getId();

        listing.setModeratedBy(actorId); listing.setModeratedAt(Instant.now()); listing.setModerationReason(reason);
        listing.setStatus(ListingStatus.CANCELLED);
        listingRepository.save(listing);
        saga.setState(SagaState.COMPENSATING);
        saga.setStep("SELLER_CANCELLATION_RELEASE_REQUESTED");
        saga.setLastError("SELLER_CANCELLED".equals(reason) ? reason : "ORGANIZER_CANCELLED");
        saga.setNextRetryAt(Instant.now().plusSeconds(10));
        sagaRepository.save(saga);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("messageId", UUID.randomUUID().toString());
        command.put("sagaId", saga.getId());
        command.put("operationId", saga.getId());
        command.put("ticketId", listing.getTicketId());
        command.put("ticketIds", listing.remainingMembers().stream().map(TicketMemberSnapshot::getTicketId).toList());
        command.put("lockTicketIds", listing.getMembers().isEmpty() ? List.of(listing.getTicketId())
                : listing.getMembers().stream().map(TicketMemberSnapshot::getTicketId).toList());
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_LISTING").aggregateId(listingId)
                    .eventType("TICKET_RELEASE_REQUESTED").topic("haloticket.ticket.release.requested.v1")
                    .payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not create listing cancellation command", exception);
        }
        return toResponse(saga);
    }

    /**
     * Kích hoạt tin sau khi vé đã được khóa thành công (tương đương kết quả khóa thành công).
     *
     * @param sagaId mã saga
     */
    @Transactional
    public void activateFromTicketLock(String sagaId) {
        handleTicketLockResult(sagaId, true);
    }

    /**
     * Xử lý kết quả khóa vé của saga đang chờ khóa. Khóa thất bại thì saga {@code FAILED} và hủy lượt tặng (nếu có). Khóa thành công với
     * lượt tặng thì tạo lời mời có hạn và phát sự kiện mời; với bán lại thì tạo tin ở trạng thái {@code ACTIVE}, {@code PENDING_APPROVAL}
     * hoặc {@code PAUSED_BY_EVENT} tùy chính sách, hoàn tất saga và phát sự kiện tương ứng.
     *
     * @param sagaId mã saga
     * @param locked {@code true} nếu {@code ticket-service} đã khóa vé
     * @throws IllegalStateException khi không tạo được tin hoặc lời mời
     */
    @Transactional
    public void handleTicketLockResult(String sagaId, boolean locked) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null);
        if (saga == null || saga.getState() != SagaState.WAITING_LOCK) return;
        if (!locked) {
            saga.setState(SagaState.FAILED); saga.setStep("LOCK_REJECTED"); saga.setLastError("TICKET_NOT_ELIGIBLE");
            sagaRepository.save(saga);
            if ("GIFT_TRANSFER".equals(saga.getType())) {
                transferRepository.findBySagaId(sagaId).ifPresent(transfer -> {
                    transfer.setStatus(TransferStatus.CANCELLED); transfer.setCancellationReason("TICKET_NOT_ELIGIBLE"); transferRepository.save(transfer);
                });
            }
            return;
        }
        try {
            var node = objectMapper.readTree(saga.getPayload());
            if ("GIFT_TRANSFER".equals(saga.getType())) {
                TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElseThrow();
                try { policyGate.requireGiftEnabled(transfer.getEventId()); }
                catch (ResponseStatusException disabled) { requestRelease(sagaId, "GIFT_DISABLED"); return; }
                String token = UUID.randomUUID().toString();
                transfer.setInvitationTokenHash(hashToken(token));
                Instant invitationExpiry = Instant.now().plusSeconds(node.path("invitationTtlHours").asInt(24) * 3600L);
                Instant policyCutoff = node.hasNonNull("giftClosesAt") ? Instant.parse(node.path("giftClosesAt").asText()) : null;
                if (policyCutoff != null && policyCutoff.isBefore(invitationExpiry)) invitationExpiry = policyCutoff;
                if (!invitationExpiry.isAfter(Instant.now())) { requestRelease(sagaId, "GIFT_CLOSED"); return; }
                transfer.setInvitationExpiresAt(invitationExpiry);
                transferRepository.save(transfer);
                saga.setDeadlineAt(transfer.getInvitationExpiresAt());
                saga.setState(SagaState.WAITING_RECIPIENT); saga.setStep("INVITATION_CREATED"); sagaRepository.save(saga);
                Map<String, Object> invitation = new LinkedHashMap<>();
                invitation.put("sagaId", sagaId);
                invitation.put("ticketId", transfer.getTicketId());
                invitation.put("ticketIds", memberIds(transfer));
                invitation.put("quantity", memberIds(transfer).size());
                invitation.put("tickets", transfer.getMembers());
                invitation.put("recipientEmail", transfer.getRecipientEmail());
                invitation.put("recipientUserId", transfer.getToUserId());
                invitation.put("message", transfer.getMessage());
                invitation.put("invitationToken", token);
                invitation.put("expiresAt", transfer.getInvitationExpiresAt());
                invitation.put("senderUserId", transfer.getFromUserId());
                String senderName = "Một người bạn trên HaloTicket";
                try {
                    var sender = userServiceClient.getUserDisplayName(transfer.getFromUserId());
                    if (sender != null && sender.fullName() != null && !sender.fullName().isBlank()) {
                        senderName = sender.fullName();
                    }
                } catch (Exception ignored) {}
                invitation.put("senderName", senderName);
                invitation.put("ticketTypeName", node.path("ticketTypeName").asText(""));
                invitation.put("section", node.path("section").asText(""));
                invitation.put("row", node.path("row").asText(""));
                invitation.put("seat", node.path("seat").asText(""));
                invitation.put("eventId", node.path("eventId").asText(""));
                outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("GIFT_INVITATION_CREATED")
                        .topic("haloticket.marketplace.gift.invited.v1").payload(objectMapper.writeValueAsString(invitation)).status(OutboxStatus.PENDING).retryCount(0).build());
                return;
            }
            Instant expiry = node.hasNonNull("closesAt") ? Instant.parse(node.get("closesAt").asText()) : saga.getDeadlineAt();
            String eventId = text(node, "eventId");
            boolean resaleAllowed = true;
            if (eventId != null) {
                try { policyGate.requireResaleEnabled(eventId); } catch (Exception e) { resaleAllowed = false; }
            }
            boolean needsApproval = node.path("requireApproval").asBoolean(false);
            ListingStatus initialStatus = needsApproval ? ListingStatus.PENDING_APPROVAL : resaleAllowed ? ListingStatus.ACTIVE : ListingStatus.PAUSED_BY_EVENT;
            String pauseReason = needsApproval || resaleAllowed ? null : "ORGANIZER_DISABLED_RESALE";
            TicketListing listing = TicketListing.builder().ticketId(node.get("ticketId").asText()).sellerId(node.get("sellerId").asText())
                    .price(new BigDecimal(node.get("price").asText())).originalPrice(decimal(node,"originalPrice")).minPriceSnapshot(decimal(node,"minPrice"))
                    .maxPriceSnapshot(decimal(node,"maxPrice")).eventId(eventId).status(initialStatus).pauseReason(pauseReason).expiresAt(expiry)
                    .serviceFeePercent(node.hasNonNull("serviceFeePercent") ? decimal(node, "serviceFeePercent") : new BigDecimal("5"))
                    .ticketTypeNameSnapshot(text(node,"ticketTypeName")).sectionSnapshot(text(node,"section"))
                    .rowSnapshot(text(node,"row")).seatSnapshot(text(node,"seat"))
                    .quantity(node.path("quantity").asInt(1))
                    .members(members(node))
                    .allowRetail(node.path("quantity").asInt(1) == 1 || node.path("allowRetail").asBoolean(true))
                    .publishedAt(needsApproval ? null : Instant.now()).policyVersion(node.hasNonNull("policyVersion") ? node.get("policyVersion").asLong() : null)
                    .termsVersion(text(node,"termsVersion")).sagaId(sagaId).build();
            listingRepository.save(listing);
            saga.setState(SagaState.COMPLETED); saga.setStep(needsApproval ? "LISTING_PENDING_APPROVAL" : "LISTING_ACTIVE"); saga.setBusinessId(listing.getId());
            sagaRepository.save(saga);
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_LISTING").aggregateId(listing.getId()).eventType("RESALE_LISTING_ACTIVATED")
                    .topic(needsApproval ? "haloticket.marketplace.listing.pending-approval.v1" : "haloticket.marketplace.listing.activated.v1").payload(saga.getPayload()).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (Exception exception) { throw new IllegalStateException("Could not activate resale listing", exception); }
    }

    /**
     * Bắt đầu saga tặng vé cho người nhận theo email. Kiểm tra khóa idempotency, điều khoản, người nhận tồn tại và khác người tặng,
     * từng vé đủ điều kiện và cùng suất diễn, chính sách tặng vé còn mở, loại vé và giới hạn số lần tặng; sau đó lưu saga,
     * lượt chuyển nhượng ở trạng thái chờ và đưa lệnh khóa vé vào outbox.
     *
     * @param actorId mã người tặng
     * @param authorization header {@code Authorization} của người tặng
     * @param idempotencyKey khóa idempotency bắt buộc
     * @param request thông tin người nhận và vé
     * @return trạng thái saga vừa tạo hoặc đã có
     * @throws ResponseStatusException khi thiếu khóa (400), thiếu người nhận, điều khoản hoặc tặng cho chính mình (422),
     *         vé không đủ điều kiện hoặc chính sách tắt, chạm giới hạn (409), dịch vụ phụ thuộc không khả dụng (503)
     */
    @Transactional
    public SagaResponse startGift(String actorId, String authorization, String idempotencyKey, CreateGiftRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        if (request == null || request.recipientEmail() == null || request.recipientEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RECIPIENT_REQUIRED");
        }
        if (!request.acceptedTerms()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "GIFT_TERMS_REQUIRED");
        List<String> ticketIds = selectedIds(request.ticketId(), request.ticketIds());
        String hash = hash(request);
        Optional<MarketplaceSaga> existing = sagaRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different request");
            return toResponse(existing.get());
        }
        String recipientEmail = request.recipientEmail().trim().toLowerCase(java.util.Locale.ROOT);
        UserServiceClient.GiftRecipient recipient;
        try { recipient = userServiceClient.getGiftRecipient(authorization, recipientEmail); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RECIPIENT_NOT_FOUND", exception); }
        if (recipient == null || recipient.userId() == null || recipient.userId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RECIPIENT_NOT_FOUND");
        }
        if (actorId.equals(recipient.userId())) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CANNOT_GIFT_SELF");
        List<TicketServiceClient.ResaleEligibilityResponse> eligibleTickets = new ArrayList<>();
        for (String ticketId : ticketIds) {
            TicketServiceClient.ResaleEligibilityResponse item;
            try { item = ticketServiceClient.getResaleEligibility(authorization, ticketId); }
            catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_SERVICE_UNAVAILABLE", exception); }
            if (item == null || !item.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
            if (!eligibleTickets.isEmpty() && (!Objects.equals(eligibleTickets.get(0).eventId(), item.eventId())
                    || !Objects.equals(eligibleTickets.get(0).showtimeId(), item.showtimeId())))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TICKETS_MUST_SHARE_SHOWTIME");
            eligibleTickets.add(item);
        }
        TicketServiceClient.ResaleEligibilityResponse eligibility = eligibleTickets.get(0);
        if (eligibility.eventId() != null) policyGate.requireGiftEnabled(eligibility.eventId());
        EventServiceClient.GiftPolicyResponse giftPolicy;
        try { giftPolicy = eventServiceClient.getGiftPolicy(authorization, eligibility.eventId()); }
        catch (FeignException.NotFound exception) { throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_POLICY_NOT_CONFIGURED"); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_SERVICE_UNAVAILABLE", exception); }
        Instant now = Instant.now();
        if (giftPolicy == null || !giftPolicy.enabled() || giftPolicy.opensAt() != null && giftPolicy.opensAt().isAfter(now)
                || giftPolicy.closesAt() != null && !giftPolicy.closesAt().isAfter(now))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_DISABLED");
        for (var item : eligibleTickets) {
            if ("SELECTED".equals(giftPolicy.ticketTypeScope()) && (giftPolicy.allowedTicketTypeIds() == null || !giftPolicy.allowedTicketTypeIds().contains(item.ticketTypeId())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
            if (giftPolicy.maxGiftCount() > 0 && item.giftTransferCount() >= giftPolicy.maxGiftCount())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_LIMIT_REACHED");
        }
        String sagaId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sagaId", sagaId);
        payload.put("ticketId", ticketIds.get(0));
        payload.put("ticketIds", ticketIds);
        payload.put("tickets", eligibleTickets.stream().map(item -> snapshot(item)).toList());
        payload.put("fromUserId", actorId);
        payload.put("recipientEmail", recipientEmail);
        payload.put("recipientUserId", recipient.userId());
        payload.put("message", request.message());
        payload.put("eventId", eligibility.eventId());
        payload.put("ticketTypeName", eligibility.ticketTypeName());
        payload.put("section", eligibility.section());
        payload.put("row", eligibility.row());
        payload.put("seat", eligibility.seat());
        payload.put("invitationTtlHours", giftPolicy.invitationTtlHours());
        payload.put("giftClosesAt", giftPolicy.closesAt());
        payload.put("requireEkyc", giftPolicy.requireEkyc());
        payload.put("maxGiftCount", giftPolicy.maxGiftCount());
        try {
            MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("GIFT_TRANSFER").ticketId(ticketIds.get(0)).actorId(actorId).state(SagaState.WAITING_LOCK).step("LOCK_REQUESTED")
                    .payload(objectMapper.writeValueAsString(payload)).idempotencyKey(idempotencyKey).requestHash(hash).deadlineAt(eligibility.closesAt()).nextRetryAt(now).retryCount(0).build();
            sagaRepository.save(saga);
            TicketTransfer transfer = TicketTransfer.builder().ticketId(ticketIds.get(0)).members(eligibleTickets.stream().map(item -> snapshot(item)).toList()).fromUserId(actorId).toUserId(recipient.userId()).recipientEmail(recipientEmail).message(request.message()).type(TransferType.GIFT).status(TransferStatus.PENDING).sagaId(sagaId).eventId(eligibility.eventId()).ticketTypeName(eligibility.ticketTypeName())
                    .requireEkyc(giftPolicy.requireEkyc()).maxGiftCount(giftPolicy.maxGiftCount()).build();
            transferRepository.save(transfer); saga.setBusinessId(transfer.getId()); sagaRepository.save(saga);
            Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId); command.put("ticketId", ticketIds.get(0)); command.put("ticketIds", ticketIds); command.put("sellerId", actorId); command.put("purpose", "GIFT");
            command.put("maxGiftCount", giftPolicy.maxGiftCount());
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_LOCK_REQUESTED").topic("haloticket.ticket.lock.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
            return toResponse(saga);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create gift saga", exception); }
    }

    /**
     * Người nhận chấp nhận quà tặng: kiểm tra token lời mời, hạn lời mời, đúng người nhận, chính sách còn mở, email khớp tài khoản
     * và eKYC (nếu yêu cầu), rồi chuyển saga sang chờ chuyển vé và đưa lệnh chuyển quyền sở hữu vào outbox.
     *
     * @param actorId mã người nhận
     * @param authorization header {@code Authorization} của người nhận
     * @param sagaId mã saga của lượt tặng
     * @param token token trong lời mời
     * @return trạng thái saga
     * @throws ResponseStatusException HTTP 409 {@code GIFT_INVITATION_INVALID} khi lời mời không hợp lệ, hết hạn hoặc sai người nhận,
     *         {@code EKYC_REQUIRED} khi chưa xác thực eKYC, HTTP 503 khi dịch vụ eKYC không khả dụng
     */
    @Transactional
    public SagaResponse acceptGift(String actorId, String authorization, String sagaId, String token) {
        MarketplaceSaga saga = sagaRepository.findByIdForUpdate(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        TicketTransfer transfer = transferRepository.findBySagaIdForUpdate(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!isInvitationTokenValid(transfer, token)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_INVITATION_INVALID");
        }
        if (saga.getState() != SagaState.WAITING_RECIPIENT || transfer.getStatus() != TransferStatus.PENDING
                || transfer.getInvitationExpiresAt() == null || !transfer.getInvitationExpiresAt().isAfter(Instant.now())
                || !actorId.equals(transfer.getToUserId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_INVITATION_INVALID");
        }
        policyGate.requireGiftEnabled(transfer.getEventId());
        UserServiceClient.CurrentUser currentUser = userServiceClient.getCurrentUser(authorization);
        if (currentUser == null || !transfer.getRecipientEmail().equalsIgnoreCase(currentUser.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_INVITATION_INVALID");
        }
        if (transfer.isRequireEkyc()) {
            UserServiceClient.ResaleSellerEligibility verified;
            try { verified = userServiceClient.getResaleEligibility(authorization); }
            catch (Exception cause) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_SERVICE_UNAVAILABLE", cause); }
            if (verified == null || !verified.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "EKYC_REQUIRED");
            transfer.setRecipientEkycVerified(true);
        }
        transfer.setToUserId(actorId); transferRepository.save(transfer); saga.setState(SagaState.WAITING_TRANSFER); saga.setStep("TRANSFER_REQUESTED"); sagaRepository.save(saga);
        Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId + ":TRANSFER"); command.put("ticketId", transfer.getTicketId()); command.put("ticketIds", memberIds(transfer)); command.put("lockTicketIds", memberIds(transfer)); command.put("fromUserId", transfer.getFromUserId()); command.put("toUserId", actorId); command.put("lockOperationId", sagaId); command.put("purpose", "GIFT");
        command.put("maxGiftCount", transfer.getMaxGiftCount());
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("TICKET_OWNERSHIP_TRANSFER_REQUESTED").topic("haloticket.ticket.ownership.transfer.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create transfer command", exception); }
        return toResponse(saga);
    }

    /**
     * Người tặng hủy lượt tặng khi người nhận chưa phản hồi; yêu cầu nhả vé.
     *
     * @param actorId mã người tặng
     * @param sagaId mã saga của lượt tặng
     * @return trạng thái saga
     * @throws ResponseStatusException HTTP 404, 403 nếu không phải người tặng, HTTP 409 {@code GIFT_NOT_CANCELLABLE} nếu không còn ở trạng thái chờ người nhận
     */
    @Transactional
    public SagaResponse cancelGift(String actorId, String sagaId) {
        MarketplaceSaga saga = sagaRepository.findByIdForUpdate(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        TicketTransfer transfer = transferRepository.findBySagaIdForUpdate(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        if (!actorId.equals(transfer.getFromUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        if (transfer.getStatus() != TransferStatus.PENDING || saga.getState() != SagaState.WAITING_RECIPIENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_NOT_CANCELLABLE");
        }
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setCancellationReason("SENDER_CANCELLED");
        transferRepository.save(transfer);
        requestRelease(sagaId, "SENDER_CANCELLED");
        return toResponse(saga);
    }

    /**
     * Người nhận từ chối quà tặng; yêu cầu nhả vé.
     *
     * @param actorId mã người nhận
     * @param sagaId mã saga của lượt tặng
     * @return trạng thái saga
     * @throws ResponseStatusException HTTP 404, 403 nếu không phải người nhận, HTTP 409 {@code GIFT_NOT_CANCELLABLE} nếu không còn ở trạng thái chờ người nhận
     */
    @Transactional
    public SagaResponse declineGift(String actorId, String sagaId) {
        MarketplaceSaga saga = sagaRepository.findByIdForUpdate(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        TicketTransfer transfer = transferRepository.findBySagaIdForUpdate(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        if (!actorId.equals(transfer.getToUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        if (transfer.getStatus() != TransferStatus.PENDING || saga.getState() != SagaState.WAITING_RECIPIENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_NOT_CANCELLABLE");
        }
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setCancellationReason("RECIPIENT_DECLINED");
        transferRepository.save(transfer);
        requestRelease(sagaId, "RECIPIENT_DECLINED");
        return toResponse(saga);
    }

    /**
     * Lấy lượt tặng đang chờ của một vé do chính người tặng gửi.
     *
     * @param actorId mã người tặng
     * @param ticketId mã vé
     * @return thông tin lượt tặng (saga, vé, trạng thái, email người nhận, hạn lời mời)
     * @throws ResponseStatusException HTTP 404 {@code GIFT_NOT_FOUND} nếu không có, HTTP 403 nếu không phải người tặng
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getActiveGiftByTicket(String actorId, String ticketId) {
        TicketTransfer transfer = transferRepository.findFirstByTicketIdAndStatus(ticketId, TransferStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        if (!actorId.equals(transfer.getFromUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaId", transfer.getSagaId());
        result.put("ticketId", transfer.getTicketId());
        result.put("status", transfer.getStatus());
        result.put("recipientEmail", transfer.getRecipientEmail());
        result.put("expiresAt", transfer.getInvitationExpiresAt());
        return result;
    }

    /**
     * Ban tổ chức hủy lượt tặng đang chờ người nhận: đánh dấu lượt chuyển {@code CANCELLED}, chuyển saga sang bù trừ
     * và đưa lệnh nhả vé vào outbox. Trả lại saga hiện tại nếu lượt tặng đã hủy.
     *
     * @param actorId mã người thực hiện
     * @param eventId mã sự kiện
     * @param sagaId mã saga của lượt tặng
     * @param reason lý do hủy, có thể bỏ trống
     * @return trạng thái saga
     * @throws ResponseStatusException HTTP 403 {@code EVENT_MISMATCH} khi lượt tặng không thuộc sự kiện, HTTP 409 {@code GIFT_NOT_CANCELLABLE}
     */
    @Transactional
    public SagaResponse cancelGiftAsOrganizer(String actorId, String eventId, String sagaId, String reason) {
        MarketplaceSaga saga = sagaRepository.findByIdForUpdate(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        TicketTransfer transfer = transferRepository.findBySagaIdForUpdate(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND"));
        if (!"GIFT_TRANSFER".equals(saga.getType()) || !Objects.equals(eventId, transfer.getEventId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EVENT_MISMATCH");
        if (transfer.getStatus() == TransferStatus.CANCELLED) return toResponse(saga);
        if (transfer.getStatus() != TransferStatus.PENDING || saga.getState() != SagaState.WAITING_RECIPIENT)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_NOT_CANCELLABLE");
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setCancellationReason(reason == null || reason.isBlank() ? "ORGANIZER_CANCELLED" : boundedReason(reason));
        transferRepository.save(transfer);
        saga.setState(SagaState.COMPENSATING); saga.setStep("ORGANIZER_GIFT_CANCELLATION_REQUESTED"); saga.setLastError("ORGANIZER_CANCELLED");
        saga.setNextRetryAt(Instant.now().plusSeconds(10)); sagaRepository.save(saga);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId);
        command.put("ticketId", transfer.getTicketId()); command.put("ticketIds", memberIds(transfer)); command.put("lockTicketIds", memberIds(transfer));
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId())
                    .eventType("TICKET_RELEASE_REQUESTED").topic("haloticket.ticket.release.requested.v1")
                    .payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not cancel gift", exception); }
        return toResponse(saga);
    }

    /**
     * Xem nội dung lời mời tặng vé; chỉ người nhận cùng token hợp lệ mới xem được. Kèm tên người tặng nếu lấy được.
     *
     * @param actorId mã người nhận
     * @param sagaId mã saga của lượt tặng
     * @param token token trong lời mời
     * @return thông tin lời mời dưới dạng map
     * @throws ResponseStatusException HTTP 404, HTTP 403 {@code GIFT_INVITATION_INVALID} khi sai người nhận hoặc token
     */
    @Transactional(readOnly = true)
    public Map<String, Object> giftInvitation(String actorId, String sagaId, String token) {
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!actorId.equals(transfer.getToUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_INVITATION_INVALID");
        }
        if (!isInvitationTokenValid(transfer, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_INVITATION_INVALID");
        }
        try {
            var payload = objectMapper.readTree(saga.getPayload());
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : java.util.List.of("ticketId", "eventId", "ticketTypeName", "section", "row", "seat", "message")) {
                result.put(key, payload.path(key).asText(""));
            }
            result.put("recipientEmail", transfer.getRecipientEmail());
            result.put("expiresAt", transfer.getInvitationExpiresAt());
            result.put("state", saga.getState());
            result.put("requireEkyc", transfer.isRequireEkyc());
            result.put("maxGiftCount", transfer.getMaxGiftCount());
            result.put("ticketIds", memberIds(transfer));
            result.put("quantity", memberIds(transfer).size());
            result.put("tickets", transfer.getMembers());
            String fromUserId = transfer.getFromUserId();
            result.put("senderUserId", fromUserId);
            try {
                var sender = userServiceClient.getUserDisplayName(fromUserId);
                result.put("senderName", sender != null ? sender.fullName() : null);
            } catch (Exception ignored) { result.put("senderName", null); }
            return result;
        } catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    /**
     * Liệt kê các lời mời tặng vé còn hiệu lực gửi tới người dùng, mới nhất trước.
     *
     * @param actorId mã người nhận
     * @return danh sách lời mời
     */
    @Transactional(readOnly = true)
    public java.util.List<Map<String, Object>> myGiftInvitations(String actorId) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (TicketTransfer transfer : transferRepository.findByToUserIdAndStatusOrderByCreatedAtDesc(actorId, TransferStatus.PENDING)) {
            if (transfer.getInvitationExpiresAt() == null || transfer.getInvitationExpiresAt().isBefore(Instant.now())) continue;
            MarketplaceSaga saga = sagaRepository.findById(transfer.getSagaId()).orElse(null);
            if (saga == null || saga.getState() != SagaState.WAITING_RECIPIENT) continue;
            try {
                var payload = objectMapper.readTree(saga.getPayload());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sagaId", saga.getId());
                item.put("eventId", payload.path("eventId").asText(""));
                item.put("ticketTypeName", payload.path("ticketTypeName").asText("Vé sự kiện"));
                item.put("section", payload.path("section").asText(""));
                item.put("row", payload.path("row").asText(""));
                item.put("seat", payload.path("seat").asText(""));
                item.put("expiresAt", transfer.getInvitationExpiresAt());
                item.put("ticketIds", memberIds(transfer));
                item.put("quantity", memberIds(transfer).size());
                item.put("tickets", transfer.getMembers());
                try {
                    var sender = userServiceClient.getUserDisplayName(transfer.getFromUserId());
                    item.put("senderName", sender != null ? sender.fullName() : null);
                } catch (Exception ignored) { item.put("senderName", null); }
                result.add(item);
            } catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
        }
        return result;
    }

    /**
     * Xử lý khi vé tặng đã chuyển quyền sở hữu: hoàn tất lượt chuyển nhượng và saga rồi phát sự kiện hoàn tất.
     * Bỏ qua nếu saga không ở trạng thái chờ chuyển vé.
     *
     * @param sagaId mã saga của lượt tặng
     */
    @Transactional
    public void handleOwnershipTransferred(String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null); if (saga == null || saga.getState() != SagaState.WAITING_TRANSFER) return;
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElse(null); if (transfer == null) return;
        transfer.setStatus(TransferStatus.COMPLETED); transfer.setTransferredAt(Instant.now()); transferRepository.save(transfer);
        saga.setState(SagaState.COMPLETED); saga.setStep("TRANSFER_COMPLETED"); sagaRepository.save(saga);
        outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("GIFT_COMPLETED").topic("haloticket.marketplace.gift.completed.v1").payload(saga.getPayload()).status(OutboxStatus.PENDING).retryCount(0).build());
    }

    /**
     * Yêu cầu nhả vé cho saga: chuyển sang bù trừ, hẹn thử lại sau 10 giây và đưa lệnh nhả vào outbox.
     * Bỏ qua saga mua vé bán lại hoặc saga đã kết thúc.
     *
     * @param sagaId mã saga
     * @param reason lý do nhả vé, được lưu vào saga
     */
    @Transactional
    public void requestRelease(String sagaId, String reason) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null);
        if (saga == null || "RESALE_PURCHASE".equals(saga.getType())
                || saga.getState() == SagaState.COMPENSATED || saga.getState() == SagaState.COMPLETED) return;
        saga.setState(SagaState.COMPENSATING); saga.setStep("RELEASE_REQUESTED"); saga.setLastError(reason); saga.setNextRetryAt(Instant.now().plusSeconds(10)); sagaRepository.save(saga);
        Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId); command.put("ticketId", saga.getTicketId()); command.put("ticketIds", sagaTicketIds(saga));
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_RELEASE_REQUESTED").topic("haloticket.ticket.release.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create release command", exception); }
    }

    /**
     * Xử lý khi vé đã được nhả: hủy tin bán lại hoặc lượt tặng còn mở, đánh dấu saga {@code COMPENSATED} và phát sự kiện
     * hủy hoặc thất bại tương ứng (người bán hoặc ban tổ chức hủy thì là sự kiện hủy).
     *
     * @param sagaId mã saga
     */
    @Transactional
    public void handleTicketReleased(String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null);
        if (saga == null || "RESALE_PURCHASE".equals(saga.getType()) || saga.getState() != SagaState.COMPENSATING) return;
        if ("RESALE_LISTING".equals(saga.getType()) && saga.getBusinessId() != null) {
            listingRepository.findById(saga.getBusinessId()).ifPresent(listing -> {
                if (listing.getStatus() == ListingStatus.ACTIVE || listing.getStatus() == ListingStatus.PAUSED_BY_EVENT || listing.getStatus() == ListingStatus.PENDING_APPROVAL) {
                    listing.setStatus(ListingStatus.CANCELLED);
                    listingRepository.save(listing);
                }
            });
        }
        transferRepository.findBySagaId(sagaId).ifPresent(transfer -> { transfer.setStatus(TransferStatus.CANCELLED); if (transfer.getCancellationReason() == null) transfer.setCancellationReason(saga.getLastError()); transferRepository.save(transfer); });
        saga.setState(SagaState.COMPENSATED); saga.setStep("RELEASE_CONFIRMED"); saga.setNextRetryAt(null); sagaRepository.save(saga);
        boolean gift = "GIFT_TRANSFER".equals(saga.getType());
        boolean sellerCancelled = "SELLER_CANCELLED".equals(saga.getLastError()) || "ORGANIZER_CANCELLED".equals(saga.getLastError());
        String eventType = gift ? "GIFT_CANCELLED" : sellerCancelled ? "RESALE_LISTING_CANCELLED" : "RESALE_LISTING_FAILED";
        String topic = gift ? "haloticket.marketplace.gift.cancelled.v1" : sellerCancelled ? "haloticket.marketplace.listing.cancelled.v1" : "haloticket.marketplace.listing.failed.v1";
        outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType(eventType).topic(topic).payload(saga.getPayload()).status(OutboxStatus.PENDING).retryCount(0).build());
    }

    /**
     * Đọc trường chuỗi từ JSON.
     *
     * @param node nút JSON
     * @param field tên trường
     * @return giá trị chuỗi, hoặc {@code null} nếu trường vắng hoặc null
     */
    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asText() : null; }
    /**
     * Đọc trường số thập phân từ JSON.
     *
     * @param node nút JSON
     * @param field tên trường
     * @return giá trị {@code BigDecimal}, hoặc {@code null} nếu trường vắng hoặc null
     */
    private static BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode node, String field) { return node.hasNonNull(field) ? new BigDecimal(node.get(field).asText()) : null; }
    /**
     * Chuyển saga thành phản hồi trả cho client.
     *
     * @param saga saga nguồn
     * @return mã saga, trạng thái, mã nghiệp vụ liên quan và lỗi gần nhất
     */
    private SagaResponse toResponse(MarketplaceSaga saga) { return new SagaResponse(saga.getId(), saga.getState(), saga.getBusinessId(), saga.getLastError()); }
    /**
     * Dựng phản hồi giữ chỗ từ giao dịch; không có danh sách vé riêng thì dùng vé gốc của tin.
     *
     * @param transaction giao dịch mua
     * @return phản hồi giữ chỗ
     */
    private ReservationResponse reservationResponse(MarketplaceTransaction transaction) {
        List<String> ids = transaction.getTicketIds() == null || transaction.getTicketIds().isEmpty()
                ? List.of(transaction.getTicketListing().getTicketId()) : List.copyOf(transaction.getTicketIds());
        return new ReservationResponse(transaction.getId(), transaction.getTicketListing().getId(), transaction.getStatus(),
                transaction.getAmount(), transaction.getCurrency(), transaction.getReservationExpiresAt(), ids.size(), ids);
    }

    /**
     * Xử lý thanh toán thất bại: hủy giao dịch đang chờ hoặc đang xử lý thanh toán và mở lại tin đang bị người mua đó giữ chỗ.
     * Bỏ qua nếu giao dịch hoặc tin không còn ở trạng thái phù hợp.
     *
     * @param transactionId mã giao dịch mua
     */
    @Transactional
    public void paymentFailed(String transactionId) {
        MarketplaceTransaction transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction == null || (transaction.getStatus() != MarketplaceTransactionStatus.PENDING_PAYMENT
                && transaction.getStatus() != MarketplaceTransactionStatus.PAYMENT_PROCESSING)) return;
        TicketListing listing = listingRepository.findByIdForUpdate(transaction.getTicketListing().getId()).orElse(null);
        if (listing == null || listing.getStatus() != ListingStatus.RESERVED
                || !transaction.getBuyerId().equals(listing.getReservedBy())) return;
        transaction.setStatus(MarketplaceTransactionStatus.CANCELLED);
        transaction.setCancelledAt(Instant.now());
        transactionRepository.save(transaction);
        listing.setStatus(resolveStatusAfterReservation(listing, Instant.now()));
        listing.setReservedBy(null); listing.setReservedUntil(null);
        listingRepository.save(listing);
    }
    /**
     * Chuẩn hóa danh sách vé được chọn, gộp mã vé cũ ({@code legacyId}) nếu chỉ có mã đơn.
     *
     * @param legacyId mã vé đơn, có thể {@code null}
     * @param ids danh sách mã vé, có thể {@code null}
     * @return danh sách bất biến các mã vé
     * @throws ResponseStatusException HTTP 422 {@code TICKET_IDS_INVALID} khi rỗng, quá 50 vé, có mã trống hoặc trùng,
     *         hoặc mã đơn không nằm trong danh sách
     */
    private static List<String> selectedIds(String legacyId, List<String> ids) {
        List<String> selected = ids == null ? (legacyId == null ? List.of() : List.of(legacyId)) : ids;
        if (selected.isEmpty() || selected.size() > 50 || selected.stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(selected).size() != selected.size()
                || legacyId != null && !legacyId.isBlank() && ids != null && !ids.contains(legacyId))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TICKET_IDS_INVALID");
        return List.copyOf(selected);
    }
    /**
     * Chụp thông tin vé từ kết quả kiểm tra điều kiện, với cờ chưa bán.
     *
     * @param item kết quả kiểm tra điều kiện của vé
     * @return bản chụp thông tin vé
     */
    private static TicketMemberSnapshot snapshot(TicketServiceClient.ResaleEligibilityResponse item) {
        return new TicketMemberSnapshot(item.ticketId(), item.ticketTypeId(), item.showtimeId(),
                item.ticketTypeName(), item.section(), item.row(), item.seat(), item.originalPrice(), false);
    }
    /**
     * Dựng danh sách bản chụp vé từ payload của saga; thiếu mảng {@code tickets} thì dựng một vé từ các trường đơn.
     *
     * @param node payload JSON của saga
     * @return danh sách bản chụp vé
     */
    private List<TicketMemberSnapshot> members(com.fasterxml.jackson.databind.JsonNode node) {
        var tickets = node.path("tickets");
        if (!tickets.isArray()) return List.of(new TicketMemberSnapshot(text(node,"ticketId"), null, null,
                text(node,"ticketTypeName"), text(node,"section"), text(node,"row"), text(node,"seat"), decimal(node,"originalPrice"), false));
        List<TicketMemberSnapshot> result = new ArrayList<>();
        for (var ticket : tickets) result.add(new TicketMemberSnapshot(text(ticket,"ticketId"), text(ticket,"ticketTypeId"),
                text(ticket,"showtimeId"), text(ticket,"ticketTypeName"), text(ticket,"section"), text(ticket,"row"),
                text(ticket,"seat"), decimal(ticket,"originalPrice"), false));
        return result;
    }
    /**
     * Lấy mã các vé của lượt chuyển nhượng; không có danh sách thành viên thì dùng mã vé gốc.
     *
     * @param transfer lượt chuyển nhượng
     * @return danh sách mã vé
     */
    private static List<String> memberIds(TicketTransfer transfer) {
        return transfer.getMembers() == null || transfer.getMembers().isEmpty() ? List.of(transfer.getTicketId())
                : transfer.getMembers().stream().map(TicketMemberSnapshot::getTicketId).toList();
    }
    /**
     * Đọc danh sách mã vé từ payload của saga; thiếu mảng {@code ticketIds} thì dùng mã vé gốc của saga.
     *
     * @param saga saga nguồn
     * @return danh sách mã vé
     * @throws IllegalStateException khi payload không phải JSON hợp lệ
     */
    private List<String> sagaTicketIds(MarketplaceSaga saga) {
        try {
            var ids = objectMapper.readTree(saga.getPayload()).path("ticketIds");
            if (!ids.isArray()) return List.of(saga.getTicketId());
            List<String> result = new ArrayList<>();
            ids.forEach(id -> result.add(id.asText()));
            return result;
        } catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    /**
     * Băm SHA-256 nội dung yêu cầu đăng bán để so khớp khi dùng lại khóa idempotency.
     *
     * @param request yêu cầu đăng bán
     * @return chuỗi hex của giá trị băm
     */
    private String hash(CreateListingRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    /**
     * Băm SHA-256 nội dung yêu cầu tặng vé để so khớp khi dùng lại khóa idempotency.
     *
     * @param request yêu cầu tặng vé
     * @return chuỗi hex của giá trị băm
     */
    private String hash(CreateGiftRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    
    /** Thiếu hash, thiếu token (hoặc token trống) và token sai đều bị từ chối như nhau để không lộ thông tin. */
    private boolean isInvitationTokenValid(TicketTransfer transfer, String token) {
        String expectedHash = transfer.getInvitationTokenHash();
        if (expectedHash == null || token == null || token.isBlank()) return false;
        return MessageDigest.isEqual(hashToken(token).getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Băm SHA-256 token lời mời để lưu và so khớp mà không lưu token gốc.
     *
     * @param token token gốc
     * @return chuỗi hex của giá trị băm
     */
    private String hashToken(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
