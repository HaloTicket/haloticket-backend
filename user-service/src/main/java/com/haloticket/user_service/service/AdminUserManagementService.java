package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.request.AdminUserCreateRequest;
import com.haloticket.user_service.dto.response.AdminAuditItem;
import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.dto.response.AdminUserStatsResponse;
import com.haloticket.user_service.entity.AdminAudit;
import com.haloticket.user_service.entity.EkycProfile;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.AdminAuditRepository;
import com.haloticket.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes an administrator makes to other people's accounts: open one, lock or unlock one, change its roles. Every change leaves a
 * row in admin_audit. Errors are ResponseStatusException with a stable code as the reason (the web page turns codes into text).
 *
 * A new account is created in Keycloak with a random password nobody sees (the adapter's registerDormant), so the person signs in
 * by choosing a password through the password-reset flow, which sends a code to the mailbox. This service sends no mail itself.
 * Roles are stored in the user table; they are not written to Keycloak (as with the role editor that existed before).
 */
@Service
@RequiredArgsConstructor
public class AdminUserManagementService {

    /** Roles an administrator may give. GUEST is the anonymous visitor, not an account role. */
    public static final Set<Role> ASSIGNABLE_ROLES = EnumSet.of(Role.CUSTOMER, Role.ORGANIZER, Role.STAFF, Role.ADMIN);

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern VN_PHONE = Pattern.compile("^0[35789]\\d{8}$");
    private static final int MAX_REASON = 500;

    private final UserRepository userRepository;
    private final KeycloakAuthPort keycloakAuthPort;
    private final AdminAuditRepository auditRepository;

    public AdminUserListItem createUser(Jwt actor, AdminUserCreateRequest request) {
        if (request == null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_BODY");
        }
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        if (email.isEmpty() || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_EMAIL");
        }
        String fullName = request.getFullName() == null ? "" : request.getFullName().trim();
        if (fullName.length() < 2 || fullName.length() > 100) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_NAME");
        }
        String phone = normalizePhone(request.getPhoneNumber());
        Set<Role> roles = validatedRoles(request.getRoles());

        if (userRepository.findByEmail(email).isPresent()) {
            throw error(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
        }
        if (phone != null && userRepository.findFirstByPhoneNumber(phone).isPresent()) {
            throw error(HttpStatus.CONFLICT, "PHONE_ALREADY_EXISTS");
        }

        try {
            keycloakAuthPort.registerDormant(email);
        } catch (AuthApplicationService.AuthException ex) {
            if (ex.status() == 409) {
                throw error(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
            }
            if (ex.status() == 503) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE");
            }
            throw error(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR");
        } catch (RuntimeException ex) {
            throw error(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> error(HttpStatus.INTERNAL_SERVER_ERROR, "USER_CREATION_FAILED"));
        user.setFullName(fullName);
        user.setPhoneNumber(phone);
        user.setRoles(new HashSet<>(roles));
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(user);

        audit(actor, AdminAudit.CREATE_USER, user, null, describeRoles(roles), null);
        return toItem(user);
    }

    @Transactional
    public void updateStatus(Jwt actor, String id, UserStatus status, String reason) {
        if (status == null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
        String cleanReason = cleanReason(reason);
        User user = find(id);
        if (user.getStatus() == status) {
            return;
        }
        if (status != UserStatus.ACTIVE) {
            if (isSelf(actor, user)) {
                throw error(HttpStatus.CONFLICT, "CANNOT_LOCK_SELF");
            }
            if (isLastActiveAdmin(user)) {
                throw error(HttpStatus.CONFLICT, "LAST_ADMIN");
            }
        }
        UserStatus before = user.getStatus();
        user.setStatus(status);
        userRepository.save(user);
        audit(actor, AdminAudit.CHANGE_STATUS, user, before == null ? null : before.name(), status.name(), cleanReason);
    }

    @Transactional
    public void updateRoles(Jwt actor, String id, Set<Role> requested) {
        Set<Role> roles = validatedRoles(requested);
        User user = find(id);
        Set<Role> before = user.getRoles() == null ? Set.of() : user.getRoles();
        if (before.equals(roles)) {
            return;
        }
        boolean losesAdmin = before.contains(Role.ADMIN) && !roles.contains(Role.ADMIN);
        if (losesAdmin) {
            if (isSelf(actor, user)) {
                throw error(HttpStatus.CONFLICT, "CANNOT_REMOVE_OWN_ADMIN");
            }
            if (isLastActiveAdmin(user)) {
                throw error(HttpStatus.CONFLICT, "LAST_ADMIN");
            }
        }
        String oldValue = describeRoles(before);
        user.setRoles(new HashSet<>(roles));
        userRepository.save(user);
        audit(actor, AdminAudit.CHANGE_ROLES, user, oldValue, describeRoles(roles), null);
    }

    @Transactional(readOnly = true)
    public AdminUserStatsResponse stats() {
        Map<String, Long> byRole = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            byRole.put(role.name(), 0L);
        }
        for (Object[] row : userRepository.countGroupedByRole()) {
            byRole.put(((Role) row[0]).name(), (Long) row[1]);
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (UserStatus status : UserStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        for (Object[] row : userRepository.countGroupedByStatus()) {
            if (row[0] != null) {
                byStatus.put(((UserStatus) row[0]).name(), (Long) row[1]);
            }
        }
        return AdminUserStatsResponse.builder().total(userRepository.count()).byRole(byRole).byStatus(byStatus).build();
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditItem> auditLog(Pageable pageable) {
        return auditRepository.findAll(pageable).map(row -> AdminAuditItem.builder()
                .id(row.getId()).actorEmail(row.getActorEmail()).action(row.getAction())
                .targetId(row.getTargetId()).targetEmail(row.getTargetEmail())
                .oldValue(row.getOldValue()).newValue(row.getNewValue()).reason(row.getReason())
                .createdAt(row.getCreatedAt()).build());
    }

    // ---- helpers

    private User find(String id) {
        return userRepository.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private static boolean isSelf(Jwt actor, User target) {
        return actor != null && actor.getSubject() != null && actor.getSubject().equals(target.getKeycloakId());
    }

    /** True when taking this account out of service (or out of the admin role) would leave no active administrator. */
    private boolean isLastActiveAdmin(User target) {
        boolean activeAdmin = target.getStatus() == UserStatus.ACTIVE
                && target.getRoles() != null && target.getRoles().contains(Role.ADMIN);
        return activeAdmin && userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) <= 1;
    }

    private static Set<Role> validatedRoles(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "ROLES_REQUIRED");
        }
        for (Role role : roles) {
            if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
                throw error(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
            }
        }
        return roles;
    }

    /** null when blank; the Vietnamese mobile form 0xxxxxxxxx otherwise (+84 and 84 prefixes are accepted). */
    static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[\\s.\\-()]", "");
        if (digits.startsWith("+84")) {
            digits = "0" + digits.substring(3);
        } else if (digits.startsWith("84") && digits.length() == 11) {
            digits = "0" + digits.substring(2);
        }
        if (!VN_PHONE.matcher(digits).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }
        return digits;
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String value = reason.trim();
        if (value.length() > MAX_REASON) {
            throw error(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG");
        }
        return value;
    }

    private static String describeRoles(Set<Role> roles) {
        return roles.stream().sorted(Comparator.comparing(Enum::name)).map(Enum::name).collect(Collectors.joining(","));
    }

    private void audit(Jwt actor, String action, User target, String oldValue, String newValue, String reason) {
        auditRepository.save(AdminAudit.builder()
                .actorKeycloakId(actor == null || actor.getSubject() == null ? "unknown" : actor.getSubject())
                .actorEmail(actor == null ? null : actor.getClaimAsString("email"))
                .action(action).targetId(target.getId()).targetEmail(target.getEmail())
                .oldValue(oldValue).newValue(newValue).reason(reason).build());
    }

    private static AdminUserListItem toItem(User user) {
        EkycProfile ekyc = user.getEkycProfile();
        return AdminUserListItem.builder()
                .id(user.getId()).email(user.getEmail()).fullName(user.getFullName()).phoneNumber(user.getPhoneNumber())
                .avatarUrl(user.getAvatarUrl()).gender(user.getGender()).status(user.getStatus()).roles(user.getRoles())
                .createdAt(user.getCreatedAt())
                .ekycStatus(ekyc != null && ekyc.getStatus() != null ? ekyc.getStatus().name() : "UNVERIFIED")
                .build();
    }

    private static ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
