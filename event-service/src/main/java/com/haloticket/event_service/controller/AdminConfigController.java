package com.haloticket.event_service.controller;

import com.haloticket.event_service.entity.EventSectionConfig;
import com.haloticket.event_service.entity.HomepageBanner;
import com.haloticket.event_service.repository.EventSectionConfigRepository;
import com.haloticket.event_service.repository.HomepageBannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Homepage configuration. Reads are public (the homepage draws from them); every write is for the platform admin. */
@RestController
@RequestMapping("/api/events/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final HomepageBannerRepository bannerRepository;
    private final EventSectionConfigRepository sectionConfigRepository;

    // homepage config (banners)
    @GetMapping("/homepage/banners")
    public ResponseEntity<List<HomepageBanner>> getBanners() {
        return ResponseEntity.ok(bannerRepository.findAll());
    }

    /** Replaces the whole list. One transaction: when a banner is refused or the save fails, the old list stays. */
    @PutMapping("/homepage/banners")
    @Transactional
    public ResponseEntity<List<HomepageBanner>> updateBanners(@RequestBody List<HomepageBanner> newBanners) {
        for (HomepageBanner banner : newBanners) {
            if (banner.getId() == null || banner.getId().isBlank()) {
                banner.setId(UUID.randomUUID().toString());
            }
            requireText(banner.getTitle(), "BANNER_TITLE_REQUIRED");
            requireLink(banner.getImageUrl(), "BANNER_IMAGE_INVALID");
            requireLink(banner.getLinkUrl(), "BANNER_LINK_INVALID");
        }
        bannerRepository.deleteAllInBatch(); // a bulk delete runs now, so the saves below do not meet the rows being replaced
        return ResponseEntity.ok(bannerRepository.saveAll(newBanners));
    }

    // homepage config (event sections)
    @GetMapping("/homepage/sections")
    public ResponseEntity<List<EventSectionConfig>> getEventSections() {
        return ResponseEntity.ok(sectionConfigRepository.findAll());
    }

    /** Replaces the whole list, in one transaction like the banners. */
    @PutMapping("/homepage/sections")
    @Transactional
    public ResponseEntity<List<EventSectionConfig>> updateEventSections(@RequestBody List<EventSectionConfig> newSections) {
        for (EventSectionConfig section : newSections) {
            if (section.getId() == null || section.getId().isBlank()) {
                section.setId(UUID.randomUUID().toString());
            }
            requireText(section.getTitle(), "SECTION_TITLE_REQUIRED");
            if (section.getLimit() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SECTION_LIMIT_INVALID");
            }
            requireLink(section.getAdsImageUrl(), "SECTION_IMAGE_INVALID");
            requireLink(section.getAdsButtonUrl(), "SECTION_LINK_INVALID");
        }
        sectionConfigRepository.deleteAllInBatch();
        return ResponseEntity.ok(sectionConfigRepository.saveAll(newSections));
    }

    private static void requireText(String value, String code) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
        }
    }

    /** empty is fine; otherwise an http(s) address, an app path starting with a slash, or an image sent inline as data */
    private static void requireLink(String value, String code) {
        if (value == null || value.isBlank()) {
            return;
        }
        String v = value.trim().toLowerCase();
        if (!(v.startsWith("http://") || v.startsWith("https://") || v.startsWith("/") || v.startsWith("data:image/"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
        }
    }
}
