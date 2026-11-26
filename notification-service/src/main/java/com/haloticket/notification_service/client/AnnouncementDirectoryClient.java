package com.haloticket.notification_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;

@FeignClient(name = "user-service", contextId = "announcementDirectoryClient", path = "/api/internal/notification-recipients")
public interface AnnouncementDirectoryClient {
    String INTERNAL_TOKEN = "X-Internal-Token";

    @GetMapping("/resolve")
    Account resolve(@RequestHeader(INTERNAL_TOKEN) String internalToken, @RequestParam String identifier);

    @GetMapping("/active/{id}")
    Account activeAccount(@RequestHeader(INTERNAL_TOKEN) String internalToken, @PathVariable String id,
                          @RequestParam Instant cutoff);

    @GetMapping("/active")
    List<String> activeIds(@RequestHeader(INTERNAL_TOKEN) String internalToken, @RequestParam Instant cutoff,
                           @RequestParam(required = false) String after, @RequestParam int limit);

    @PostMapping("/active/match")
    List<String> matchActiveIds(@RequestHeader(INTERNAL_TOKEN) String internalToken, @RequestParam Instant cutoff,
                                @RequestBody List<String> ids);

    @PostMapping("/active/match-buyers")
    List<String> matchActiveBuyerIds(@RequestHeader(INTERNAL_TOKEN) String internalToken, @RequestParam Instant cutoff,
                                     @RequestBody List<String> ids);

    record Account(String id) {}
}
