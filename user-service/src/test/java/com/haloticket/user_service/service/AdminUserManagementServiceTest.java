package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.request.AdminUserCreateRequest;
import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.dto.response.AdminUserStatsResponse;
import com.haloticket.user_service.entity.AdminAudit;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.AdminAuditRepository;
import com.haloticket.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserManagementServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final KeycloakAuthPort keycloak = mock(KeycloakAuthPort.class);
    private final AdminAuditRepository audit = mock(AdminAuditRepository.class);
    private final AdminUserManagementService service = new AdminUserManagementService(userRepository, keycloak, audit);

    private final Jwt admin = Jwt.withTokenValue("t").header("alg", "none").subject("kc-admin").claim("email", "admin@halo.vn").build();

    @BeforeEach
    void saves() {
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static AdminUserCreateRequest request(String email, String name, String phone, Role... roles) {
        AdminUserCreateRequest r = new AdminUserCreateRequest();
        r.setEmail(email);
        r.setFullName(name);
        r.setPhoneNumber(phone);
        r.setRoles(new HashSet<>(List.of(roles)));
        return r;
    }

    private static User user(String id, String keycloakId, UserStatus status, Role... roles) {
        return User.builder().id(id).keycloakId(keycloakId).email(id + "@halo.vn").status(status).roles(new HashSet<>(List.of(roles))).build();
    }

    private static void assertRejected(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(status.value());
            assertThat(ex.getReason()).isEqualTo(code);
        });
    }

    // ---- create

    @Test
    void createsADormantAccountThenFillsInNamePhoneAndRolesAndWritesAnAuditRow() {
        User fresh = user("new-id", "kc-new", UserStatus.ACTIVE, Role.CUSTOMER);
        fresh.setEmail("new@halo.vn");
        when(userRepository.findByEmail("new@halo.vn")).thenReturn(Optional.empty(), Optional.of(fresh));
        when(userRepository.findFirstByPhoneNumber("0912345678")).thenReturn(Optional.empty());

        AdminUserListItem created = service.createUser(admin, request(" New@Halo.vn ", " Nguyễn Văn A ", "+84 912 345 678", Role.ORGANIZER, Role.STAFF));

        verify(keycloak).registerDormant("new@halo.vn");
        verify(keycloak, never()).register(any(), any());
        assertThat(created.getFullName()).isEqualTo("Nguyễn Văn A");
        assertThat(created.getPhoneNumber()).isEqualTo("0912345678");
        assertThat(created.getRoles()).containsExactlyInAnyOrder(Role.ORGANIZER, Role.STAFF);
        ArgumentCaptor<AdminAudit> row = ArgumentCaptor.forClass(AdminAudit.class);
        verify(audit).save(row.capture());
        assertThat(row.getValue().getAction()).isEqualTo(AdminAudit.CREATE_USER);
        assertThat(row.getValue().getActorKeycloakId()).isEqualTo("kc-admin");
        assertThat(row.getValue().getNewValue()).isEqualTo("ORGANIZER,STAFF");
    }

    @Test
    void aDuplicateEmailOrPhoneIsAConflictAndNothingIsCreated() {
        when(userRepository.findByEmail("known@halo.vn")).thenReturn(Optional.of(user("x", "kc-x", UserStatus.ACTIVE, Role.CUSTOMER)));
        assertRejected(() -> service.createUser(admin, request("known@halo.vn", "Known", null, Role.CUSTOMER)), HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");

        when(userRepository.findByEmail("free@halo.vn")).thenReturn(Optional.empty());
        when(userRepository.findFirstByPhoneNumber("0912345678")).thenReturn(Optional.of(user("y", "kc-y", UserStatus.ACTIVE, Role.CUSTOMER)));
        assertRejected(() -> service.createUser(admin, request("free@halo.vn", "Free", "0912345678", Role.CUSTOMER)), HttpStatus.CONFLICT, "PHONE_ALREADY_EXISTS");

        verify(keycloak, never()).registerDormant(any());
    }

    @Test
    void anEmailKeycloakAlreadyHasIsAlsoAConflict() {
        when(userRepository.findByEmail("ghost@halo.vn")).thenReturn(Optional.empty());
        doThrow(new AuthApplicationService.AuthException("USER_ALREADY_EXISTS", "exists", 409)).when(keycloak).registerDormant("ghost@halo.vn");
        assertRejected(() -> service.createUser(admin, request("ghost@halo.vn", "Ghost", null, Role.CUSTOMER)), HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
        doThrow(new AuthApplicationService.AuthException("KEYCLOAK_UNAVAILABLE", "down", 503)).when(keycloak).registerDormant("down@halo.vn");
        when(userRepository.findByEmail("down@halo.vn")).thenReturn(Optional.empty());
        assertRejected(() -> service.createUser(admin, request("down@halo.vn", "Down", null, Role.CUSTOMER)), HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE");
    }

    @Test
    void invalidInputIsRejectedBeforeAnythingIsCreated() {
        assertRejected(() -> service.createUser(admin, request("not-an-email", "Name", null, Role.CUSTOMER)), HttpStatus.BAD_REQUEST, "INVALID_EMAIL");
        assertRejected(() -> service.createUser(admin, request("a@halo.vn", " ", null, Role.CUSTOMER)), HttpStatus.BAD_REQUEST, "INVALID_NAME");
        assertRejected(() -> service.createUser(admin, request("a@halo.vn", "Name", "12345", Role.CUSTOMER)), HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        assertRejected(() -> service.createUser(admin, request("a@halo.vn", "Name", null)), HttpStatus.BAD_REQUEST, "ROLES_REQUIRED");
        assertRejected(() -> service.createUser(admin, request("a@halo.vn", "Name", null, Role.GUEST)), HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        verify(keycloak, never()).registerDormant(any());
    }

    @Test
    void phoneNumbersAreNormalisedToTheLocalForm() {
        assertThat(AdminUserManagementService.normalizePhone("0912 345 678")).isEqualTo("0912345678");
        assertThat(AdminUserManagementService.normalizePhone("84912345678")).isEqualTo("0912345678");
        assertThat(AdminUserManagementService.normalizePhone("+84-912-345-678")).isEqualTo("0912345678");
        assertThat(AdminUserManagementService.normalizePhone("  ")).isNull();
    }

    // ---- status

    @Test
    void anAdminCannotLockTheirOwnAccount() {
        when(userRepository.findById("me")).thenReturn(Optional.of(user("me", "kc-admin", UserStatus.ACTIVE, Role.ADMIN)));
        assertRejected(() -> service.updateStatus(admin, "me", UserStatus.BANNED, null), HttpStatus.CONFLICT, "CANNOT_LOCK_SELF");
        verify(userRepository, never()).save(any());
    }

    @Test
    void theLastActiveAdministratorCannotBeLocked() {
        when(userRepository.findById("other")).thenReturn(Optional.of(user("other", "kc-other", UserStatus.ACTIVE, Role.ADMIN)));
        when(userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).thenReturn(1L);
        assertRejected(() -> service.updateStatus(admin, "other", UserStatus.INACTIVE, null), HttpStatus.CONFLICT, "LAST_ADMIN");
    }

    @Test
    void lockingAnotherUserIsAuditedWithTheReason() {
        User target = user("u1", "kc-u1", UserStatus.ACTIVE, Role.CUSTOMER);
        when(userRepository.findById("u1")).thenReturn(Optional.of(target));

        service.updateStatus(admin, "u1", UserStatus.BANNED, "  spam  ");

        assertThat(target.getStatus()).isEqualTo(UserStatus.BANNED);
        ArgumentCaptor<AdminAudit> row = ArgumentCaptor.forClass(AdminAudit.class);
        verify(audit).save(row.capture());
        assertThat(row.getValue().getAction()).isEqualTo(AdminAudit.CHANGE_STATUS);
        assertThat(row.getValue().getOldValue()).isEqualTo("ACTIVE");
        assertThat(row.getValue().getNewValue()).isEqualTo("BANNED");
        assertThat(row.getValue().getReason()).isEqualTo("spam");
    }

    @Test
    void settingTheSameStatusChangesNothingAndWritesNoAuditRow() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(user("u1", "kc-u1", UserStatus.ACTIVE, Role.CUSTOMER)));
        service.updateStatus(admin, "u1", UserStatus.ACTIVE, null);
        verify(userRepository, never()).save(any());
        verify(audit, never()).save(any());
    }

    @Test
    void unlockingIsAllowedEvenForAnAdminWhoIsTheOnlyOne() {
        User target = user("u2", "kc-u2", UserStatus.INACTIVE, Role.ADMIN);
        when(userRepository.findById("u2")).thenReturn(Optional.of(target));
        service.updateStatus(admin, "u2", UserStatus.ACTIVE, null);
        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void anUnknownUserIsNotFound() {
        when(userRepository.findById("nope")).thenReturn(Optional.empty());
        assertRejected(() -> service.updateStatus(admin, "nope", UserStatus.BANNED, null), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    // ---- roles

    @Test
    void anEmptyRoleSetIsRefused() {
        assertRejected(() -> service.updateRoles(admin, "u1", Set.of()), HttpStatus.BAD_REQUEST, "ROLES_REQUIRED");
        assertRejected(() -> service.updateRoles(admin, "u1", null), HttpStatus.BAD_REQUEST, "ROLES_REQUIRED");
    }

    @Test
    void anAdminCannotRemoveTheirOwnAdminRole() {
        when(userRepository.findById("me")).thenReturn(Optional.of(user("me", "kc-admin", UserStatus.ACTIVE, Role.ADMIN, Role.CUSTOMER)));
        assertRejected(() -> service.updateRoles(admin, "me", Set.of(Role.CUSTOMER)), HttpStatus.CONFLICT, "CANNOT_REMOVE_OWN_ADMIN");
    }

    @Test
    void theLastActiveAdministratorKeepsTheRole() {
        when(userRepository.findById("other")).thenReturn(Optional.of(user("other", "kc-other", UserStatus.ACTIVE, Role.ADMIN)));
        when(userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).thenReturn(1L);
        assertRejected(() -> service.updateRoles(admin, "other", Set.of(Role.CUSTOMER)), HttpStatus.CONFLICT, "LAST_ADMIN");
    }

    @Test
    void removingAdminFromAnotherAdminIsAllowedWhenAnotherOneRemainsAndIsAudited() {
        User target = user("u3", "kc-u3", UserStatus.ACTIVE, Role.ADMIN);
        when(userRepository.findById("u3")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).thenReturn(2L);

        service.updateRoles(admin, "u3", Set.of(Role.STAFF, Role.CUSTOMER));

        assertThat(target.getRoles()).containsExactlyInAnyOrder(Role.STAFF, Role.CUSTOMER);
        ArgumentCaptor<AdminAudit> row = ArgumentCaptor.forClass(AdminAudit.class);
        verify(audit).save(row.capture());
        assertThat(row.getValue().getOldValue()).isEqualTo("ADMIN");
        assertThat(row.getValue().getNewValue()).isEqualTo("CUSTOMER,STAFF");
    }

    @Test
    void guestIsNotAnAssignableRole() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(user("u1", "kc-u1", UserStatus.ACTIVE, Role.CUSTOMER)));
        assertRejected(() -> service.updateRoles(admin, "u1", Set.of(Role.GUEST)), HttpStatus.BAD_REQUEST, "INVALID_ROLE");
    }

    // ---- stats

    @Test
    void statsListEveryRoleAndStatusEvenWhenNobodyHasIt() {
        when(userRepository.count()).thenReturn(7L);
        when(userRepository.countGroupedByRole()).thenReturn(List.of(new Object[]{Role.CUSTOMER, 5L}, new Object[]{Role.ADMIN, 2L}));
        when(userRepository.countGroupedByStatus()).thenReturn(List.<Object[]>of(new Object[]{UserStatus.ACTIVE, 7L}));

        AdminUserStatsResponse stats = service.stats();

        assertThat(stats.getTotal()).isEqualTo(7L);
        assertThat(stats.getByRole()).containsEntry("CUSTOMER", 5L).containsEntry("ADMIN", 2L).containsEntry("ORGANIZER", 0L).containsEntry("STAFF", 0L);
        assertThat(stats.getByStatus()).containsEntry("ACTIVE", 7L).containsEntry("BANNED", 0L);
    }
}
