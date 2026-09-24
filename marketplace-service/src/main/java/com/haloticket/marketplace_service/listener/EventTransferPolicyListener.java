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

@Component @RequiredArgsConstructor @Slf4j
public class EventTransferPolicyListener {
    private final EventTransferPolicyStateRepository repo;
    private final com.haloticket.marketplace_service.repository.TicketListingRepository listingRepository;
    private final ObjectMapper mapper;

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
            throw new RuntimeException(e); // trigger Kafka retry
        }
    }

    private Instant parseInstant(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return (f.isNull() || f.isMissingNode()) ? null : Instant.parse(f.asText());
    }
}
