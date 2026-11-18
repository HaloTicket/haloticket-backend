package com.haloticket.marketplace_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.entity.EventTransferPolicyState;
import com.haloticket.marketplace_service.repository.EventTransferPolicyStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * Lắng nghe sự kiện đổi chính sách chuyển nhượng từ {@code event-service} và cập nhật bản sao chính sách trong
 * {@code EventTransferPolicyState}. Khi bán lại bị tắt thì tạm dừng các tin đang bán; khi bật lại thì khôi phục.
 */
@Component @RequiredArgsConstructor @Slf4j
public class EventTransferPolicyListener {
    private final EventTransferPolicyStateRepository repo;
    private final com.haloticket.marketplace_service.repository.TicketListingRepository listingRepository;
    private final ObjectMapper mapper;

    /**
     * Xử lý một thông điệp đổi chính sách (bán lại hoặc tặng). Bỏ qua thông điệp có phiên bản cũ hơn hoặc bằng phiên bản
     * đã áp dụng. Với {@code RESALE} bị tắt, chuyển các tin {@code ACTIVE} sang {@code PAUSED_BY_EVENT}; khi bật lại, các tin bị
     * tạm dừng bởi ban tổ chức được chuyển về {@code ACTIVE} hoặc {@code EXPIRED} nếu đã quá hạn.
     *
     * @param message nội dung JSON của sự kiện (eventId, feature, version, rules)
     * @throws RuntimeException khi xử lý lỗi, để Kafka thử lại thông điệp
     */
    @KafkaListener(topics = "haloticket.event.transfer-policy.changed.v1", groupId = "marketplace-transfer-policy")
    @Transactional
    public void onPolicyChanged(String message) {
        try {
            JsonNode node = mapper.readTree(message);
            String eventId = node.path("eventId").asText();
            String feature = node.path("feature").asText();
            long version = node.path("version").asLong();
            JsonNode rules = node.path("rules");

            EventTransferPolicyState state = repo.findByEventIdForUpdate(eventId)
                    .orElseGet(() -> EventTransferPolicyState.builder()
                            .eventId(eventId).resaleEnabled(true).giftEnabled(true).updatedAt(Instant.now()).build());

            if ("RESALE".equals(feature)) {
                if (state.getResaleVersion() != null && state.getResaleVersion() >= version) {
                    log.debug("Ignoring stale RESALE policy v{} for event {}", version, eventId);
                    return;
                }
                state.setResaleEnabled(rules.path("enabled").asBoolean(true));
                state.setResaleVersion(version);
                state.setResaleOpensAt(parseInstant(rules, "opensAt"));
                state.setResaleClosesAt(parseInstant(rules, "closesAt"));
            } else if ("GIFT".equals(feature)) {
                if (state.getGiftVersion() != null && state.getGiftVersion() >= version) {
                    log.debug("Ignoring stale GIFT policy v{} for event {}", version, eventId);
                    return;
                }
                state.setGiftEnabled(rules.path("enabled").asBoolean(true));
                state.setGiftVersion(version);
                state.setGiftOpensAt(parseInstant(rules, "opensAt"));
                state.setGiftClosesAt(parseInstant(rules, "closesAt"));
            }
            state.setUpdatedAt(Instant.now());
            repo.save(state);
            log.info("Updated {} transfer policy gate for event {} to version {}", feature, eventId, version);

            if ("RESALE".equals(feature)) {
                if (!state.isResaleEnabled()) {
                    var activeListings = listingRepository.findByEventIdAndStatus(eventId, com.haloticket.marketplace_service.enums.ListingStatus.ACTIVE);
                    for (var l : activeListings) {
                        l.setStatus(com.haloticket.marketplace_service.enums.ListingStatus.PAUSED_BY_EVENT);
                        l.setPauseReason("ORGANIZER_DISABLED_RESALE");
                    }
                    listingRepository.saveAll(activeListings);
                    log.info("Paused {} active listings for event {}", activeListings.size(), eventId);
                } else {
                    var pausedListings = listingRepository.findByEventIdAndStatus(eventId, com.haloticket.marketplace_service.enums.ListingStatus.PAUSED_BY_EVENT);
                    Instant now = Instant.now();
                    java.util.List<com.haloticket.marketplace_service.entity.TicketListing> toResume = new java.util.ArrayList<>();
                    for (var l : pausedListings) {
                        if ("ORGANIZER_DISABLED_RESALE".equals(l.getPauseReason())) {
                            if (l.getExpiresAt() == null || l.getExpiresAt().isAfter(now)) {
                                l.setStatus(com.haloticket.marketplace_service.enums.ListingStatus.ACTIVE);
                                l.setPauseReason(null);
                            } else {
                                l.setStatus(com.haloticket.marketplace_service.enums.ListingStatus.EXPIRED);
                            }
                            toResume.add(l);
                        }
                    }
                    listingRepository.saveAll(toResume);
                    log.info("Resumed/updated {} paused listings for event {}", toResume.size(), eventId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process transfer policy message: {}", message, e);
            throw new RuntimeException(e); // ném lại để Kafka thử xử lý lại thông điệp
        }
    }

    /**
     * Đọc một trường thời gian dạng ISO-8601 từ JSON.
     *
     * @param node nút JSON chứa trường
     * @param field tên trường
     * @return thời điểm đã phân tích, hoặc {@code null} nếu trường vắng hoặc null
     */
    private Instant parseInstant(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return (f.isNull() || f.isMissingNode()) ? null : Instant.parse(f.asText());
    }
}
