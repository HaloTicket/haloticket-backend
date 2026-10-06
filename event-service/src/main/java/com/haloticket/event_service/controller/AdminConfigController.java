package com.haloticket.event_service.controller;

import com.haloticket.event_service.entity.EventSectionConfig;
import com.haloticket.event_service.entity.HomepageBanner;
import com.haloticket.event_service.entity.SystemConfig;
import com.haloticket.event_service.repository.EventSectionConfigRepository;
import com.haloticket.event_service.repository.HomepageBannerRepository;
import com.haloticket.event_service.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final SystemConfigRepository systemConfigRepository;
    private final HomepageBannerRepository bannerRepository;
    private final EventSectionConfigRepository sectionConfigRepository;

    private static final String GLOBAL_CONFIG_ID = "global_config";

    // system config
    @GetMapping("/system-config")
    public ResponseEntity<SystemConfig> getSystemConfig() {
        SystemConfig config = systemConfigRepository.findById(GLOBAL_CONFIG_ID).orElseGet(() -> {
            SystemConfig newConfig = new SystemConfig(GLOBAL_CONFIG_ID, "5", "3", "strict", "off");
            return systemConfigRepository.save(newConfig);
        });
        return ResponseEntity.ok(config);
    }

    @PutMapping("/system-config")
    public ResponseEntity<SystemConfig> updateSystemConfig(@RequestBody SystemConfig config) {
        config.setId(GLOBAL_CONFIG_ID);
        return ResponseEntity.ok(systemConfigRepository.save(config));
    }

    // homepage config (banners)
    @GetMapping("/homepage/banners")
    public ResponseEntity<List<HomepageBanner>> getBanners() {
        return ResponseEntity.ok(bannerRepository.findAll());
    }

    @PutMapping("/homepage/banners")
    public ResponseEntity<List<HomepageBanner>> updateBanners(@RequestBody List<HomepageBanner> newBanners) {
        bannerRepository.deleteAll();
        return ResponseEntity.ok(bannerRepository.saveAll(newBanners));
    }

    // homepage config (event sections)
    @GetMapping("/homepage/sections")
    public ResponseEntity<List<EventSectionConfig>> getEventSections() {
        return ResponseEntity.ok(sectionConfigRepository.findAll());
    }

    @PutMapping("/homepage/sections")
    public ResponseEntity<List<EventSectionConfig>> updateEventSections(@RequestBody List<EventSectionConfig> newSections) {
        sectionConfigRepository.deleteAll();
        return ResponseEntity.ok(sectionConfigRepository.saveAll(newSections));
    }
}
