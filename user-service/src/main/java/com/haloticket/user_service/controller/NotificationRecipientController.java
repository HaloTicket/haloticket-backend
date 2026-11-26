package com.haloticket.user_service.controller;

import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/internal/notification-recipients")
public class NotificationRecipientController {
    private static final String TOKEN_HEADER = "X-Internal-Token";
    private static final ZoneId STORED_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_PAGE = 200;

    private final UserRepository users;
    private final byte[] internalToken;

    public NotificationRecipientController(UserRepository users,
            @Value("${authz.event-scope.internal-token:}") String internalToken) {
        this.users = users;
        this.internalToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/resolve")
    public Account resolve(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                           @RequestParam String identifier) {
        requireInternal(token);
        return users.findById(identifier)
                .or(() -> users.findByKeycloakId(identifier))
                .or(() -> identifier.contains("@") ? users.findByEmail(identifier) : java.util.Optional.empty())
                .map(user -> new Account(user.getId()))
                .orElseThrow(NotificationRecipientController::missingAccount);
    }

    @GetMapping("/active/{id}")
    public Account activeAccount(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                 @PathVariable String id, @RequestParam Instant cutoff) {
        requireInternal(token);
        return users.findById(id)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .filter(user -> user.getCreatedAt() == null || !user.getCreatedAt().isAfter(atDatabaseTime(cutoff)))
                .map(user -> new Account(user.getId()))
                .orElseThrow(NotificationRecipientController::missingAccount);
    }

    @GetMapping("/active")
    public List<String> activeIds(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                  @RequestParam Instant cutoff, @RequestParam(defaultValue = "") String after,
                                  @RequestParam(defaultValue = "200") int limit) {
        requireInternal(token);
        if (limit < 1 || limit > MAX_PAGE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        return users.findActiveNotificationRecipientIds(UserStatus.ACTIVE, atDatabaseTime(cutoff), after,
                PageRequest.of(0, limit));
    }

    @PostMapping("/active/match")
    public List<String> activeMatches(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                      @RequestParam Instant cutoff, @RequestBody List<String> ids) {
        requireInternal(token);
        if (ids.size() > MAX_PAGE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        return ids.isEmpty() ? List.of() : users.matchActiveNotificationRecipientIds(UserStatus.ACTIVE, atDatabaseTime(cutoff), ids);
    }

    @PostMapping("/active/match-buyers")
    public List<String> activeBuyerMatches(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                           @RequestParam Instant cutoff, @RequestBody List<String> ids) {
        requireInternal(token);
        if (ids.size() > MAX_PAGE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        return ids.isEmpty() ? List.of() : users.matchActiveNotificationBuyerIds(UserStatus.ACTIVE, atDatabaseTime(cutoff), ids);
    }

    private void requireInternal(String token) {
        if (internalToken.length == 0 || token == null
                || !MessageDigest.isEqual(internalToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private static LocalDateTime atDatabaseTime(Instant cutoff) {
        return LocalDateTime.ofInstant(cutoff, STORED_TIME_ZONE);
    }

    private static ResponseStatusException missingAccount() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND");
    }

    public record Account(String id) {}
}
