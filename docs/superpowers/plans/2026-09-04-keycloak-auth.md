# Keycloak Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement HaloTicket registration with password policy and email OTP controls, login/logout, forgot-password with email + phone OTP, protected routes, Keycloak identity management, and `user-service` database persistence.

**Architecture:** Keycloak owns the final identity, credential hash, sessions, and JWTs. The backend owns pending registration/reset challenges, OTP hashing, expiry/attempt/rate-limit state, email delivery orchestration, and the HaloTicket `users` record. The web app uses backend auth challenge APIs for email/password registration and forgot-password, then OIDC Authorization Code + PKCE for login. The backend gateway validates JWTs and `user-service` upserts an internal `users` record keyed by Keycloak `sub`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Cloud Gateway, Spring Security OAuth2 Resource Server, PostgreSQL, Keycloak 24.0.4, React 19, TypeScript, Vite, React Router, Axios, `oidc-client-ts`.

**Spec:** `docs/superpowers/specs/2026-09-04-keycloak-auth-design.md`

## Global Constraints

- Backend repo path: `D:\HaloTicket\HaloTicket_Repo\haloticket-backend`
- Web repo path: `D:\HaloTicket\HaloTicket_Repo\haloticket-web`
- Backend branch: `feature/keycloak-auth`
- Web branch: `feature/keycloak-auth`
- Keycloak realm: `haloticket-realm`
- Web Keycloak client: `haloticket-web`
- Backend user key: JWT claim `sub` stored in `users.keycloak_id`
- Role mapping: `ROLE_USER` to `CUSTOMER`, `ROLE_ORGANIZER` to `ORGANIZER`, `ROLE_STAFF` to `STAFF`, `ROLE_ADMIN` to `ADMIN`
- Do not store passwords or use them for HaloTicket authentication in web/backend; Keycloak remains the credential authority.
- Registration/reset password may transit `user-service` only in memory for the Keycloak Admin API call; never persist or log plaintext.
- Do not use Keycloak's default email-verification link as a substitute for the required 6-digit OTP.
- Do not split `haloticket-web` into separate client/admin applications.
- Use `src/services/axiosClient.ts` for web API calls.

---

## File Structure

Backend files:

- Modify `user-service/pom.xml`: add OAuth2 resource server and security test dependencies.
- Modify `user-service/src/main/resources/application.yaml`: add Keycloak issuer config.
- Modify `user-service/src/main/resources/application.yaml`: add Keycloak Admin client and mail/rate-limit settings from environment.
- Create migration/entity/repository for `auth_otp_challenges` and registration/reset services.
- Create auth challenge endpoints for registration, resend, verify, forgot-password, and password completion.
- Create Keycloak Admin API adapter and email OTP sender abstraction.
- Create `user-service/src/main/java/com/haloticket/user_service/config/SecurityConfig.java`: resource server JWT config.
- Create `user-service/src/main/java/com/haloticket/user_service/config/JwtRoleConverter.java`: map Keycloak roles to backend `Role`.
- Create `user-service/src/main/java/com/haloticket/user_service/dto/request/UserProfileUpdateRequest.java`: editable profile payload.
- Modify `user-service/src/main/java/com/haloticket/user_service/dto/response/UserResponse.java`: include `keycloakId`.
- Modify `user-service/src/main/java/com/haloticket/user_service/service/UserService.java`: add `syncCurrentUser` and `updateCurrentUser`.
- Modify `user-service/src/main/java/com/haloticket/user_service/service/impl/UserServiceImpl.java`: implement create/get/sync/update mapping.
- Modify `user-service/src/main/java/com/haloticket/user_service/controller/UserController.java`: add `/me` endpoints and protect old keycloak-id lookup.
- Create `user-service/src/test/java/com/haloticket/user_service/service/impl/UserServiceImplTest.java`: unit tests for sync/update.
- Create `user-service/src/test/java/com/haloticket/user_service/controller/UserControllerTest.java`: controller tests with mock JWT.
- Create unit/integration tests for password policy, OTP expiry/single-use, resend cooldown, attempt limits, quota and non-enumerating reset behavior.
- Modify `api-gateway/src/main/java/com/haloticket/api_gateway/config/SecurityConfig.java`: require authentication for protected APIs.
- Modify `api-gateway/src/test/java/com/haloticket/api_gateway/ApiGatewayApplicationTests.java`: smoke test security context loads.

Web files:

- Modify `package.json` and `package-lock.json`: add `oidc-client-ts`.
- Modify `.env.example`: add Keycloak variables.
- Modify `src/config/index.ts`: expose Keycloak config.
- Create `src/features/auth/types.ts`: auth/user types.
- Create `src/features/auth/oidc.ts`: OIDC `UserManager` setup.
- Create `src/features/auth/authStorage.ts`: token storage helpers compatible with current Axios client.
- Create `src/features/auth/authApi.ts`: `getCurrentUser` service using `axiosClient`.
- Create `src/features/auth/AuthContext.tsx`: provider and hook.
- Create `src/features/auth/AuthCallback.tsx`: callback route.
- Create `src/features/auth/ProtectedRoute.tsx`: route guard.
- Modify `src/features/auth/Login.tsx`: trigger Keycloak login.
- Modify `src/features/auth/Register.tsx`: trigger Keycloak registration.
- Create registration/password-reset challenge screens and API client; do not place raw password in URL or localStorage.
- Modify `src/routes/paths.ts`: add `AUTH_CALLBACK`.
- Modify `src/routes/index.tsx`: add callback and protected route wrappers.
- Modify `src/components/Header.tsx`: show login/register or user/logout state.
- Modify `src/main.tsx`: wrap app with `AuthProvider`.

---

### Task 1: Backend user-service security dependencies

**Files:**
- Modify: `user-service/pom.xml`
- Modify: `user-service/src/main/resources/application.yaml`

**Interfaces:**
- Consumes: existing Keycloak issuer `http://localhost:9080/realms/haloticket-realm`
- Produces: Spring beans can decode JWT in `user-service`

- [ ] **Step 1: Add failing dependency check**

Run:

```powershell
Select-String -Path user-service\pom.xml -Pattern "spring-boot-starter-oauth2-resource-server"
```

Expected: no match.

- [ ] **Step 2: Add dependencies to `user-service/pom.xml`**

Add inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Add issuer config**

Add to `user-service/src/main/resources/application.yaml`:

```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:9080/realms/haloticket-realm}
```

Keep it under the existing `spring:` key next to `application`, `datasource`, and `jpa`.

- [ ] **Step 4: Verify Maven resolves**

Run:

```powershell
cmd /c mvnw.cmd test -DskipTests
```

from `user-service`.

Expected: build success.

- [ ] **Step 5: Commit backend dependency setup**

```powershell
git add user-service\pom.xml user-service\src\main\resources\application.yaml
git commit -m "chore: add user service jwt resource server"
```

---

### Task 2: Backend JWT security and role mapping

**Files:**
- Create: `user-service/src/main/java/com/haloticket/user_service/config/SecurityConfig.java`
- Create: `user-service/src/main/java/com/haloticket/user_service/config/JwtRoleConverter.java`
- Test: `user-service/src/test/java/com/haloticket/user_service/config/JwtRoleConverterTest.java`

**Interfaces:**
- Consumes: JWT claim `roles: string[]`
- Produces: authenticated requests and backend role authorities named `ROLE_CUSTOMER`, `ROLE_ORGANIZER`, `ROLE_STAFF`, `ROLE_ADMIN`

- [ ] **Step 1: Write failing role converter test**

Create `JwtRoleConverterTest.java`:

```java
package com.haloticket.user_service.config;

import com.haloticket.user_service.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    @Test
    void mapsKeycloakRolesToApplicationRoles() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("keycloak-user-id")
                .claim("roles", List.of("ROLE_USER", "ROLE_ORGANIZER", "ignored"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        Set<Role> roles = converter.toApplicationRoles(jwt);

        assertThat(roles).containsExactlyInAnyOrder(Role.CUSTOMER, Role.ORGANIZER);
    }

    @Test
    void defaultsToCustomerWhenTokenHasNoKnownRole() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("keycloak-user-id")
                .claim("roles", List.of("unknown"))
                .build();

        assertThat(converter.toApplicationRoles(jwt)).containsExactly(Role.CUSTOMER);
    }
}
```

- [ ] **Step 2: Run failing test**

```powershell
cmd /c mvnw.cmd test -Dtest=JwtRoleConverterTest
```

Expected: fail because `JwtRoleConverter` does not exist.

- [ ] **Step 3: Implement `JwtRoleConverter`**

```java
package com.haloticket.user_service.config;

import com.haloticket.user_service.enums.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtRoleConverter implements Converter<Jwt, JwtAuthenticationToken> {

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = toApplicationRoles(jwt).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toSet());
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    public Set<Role> toApplicationRoles(Jwt jwt) {
        List<String> keycloakRoles = jwt.getClaimAsStringList("roles");
        Set<Role> roles = new HashSet<>();

        if (keycloakRoles != null) {
            for (String keycloakRole : keycloakRoles) {
                switch (keycloakRole) {
                    case "ROLE_USER" -> roles.add(Role.CUSTOMER);
                    case "ROLE_ORGANIZER" -> roles.add(Role.ORGANIZER);
                    case "ROLE_STAFF" -> roles.add(Role.STAFF);
                    case "ROLE_ADMIN" -> roles.add(Role.ADMIN);
                    default -> {
                    }
                }
            }
        }

        if (roles.isEmpty()) {
            roles.add(Role.CUSTOMER);
        }

        return roles;
    }
}
```

- [ ] **Step 4: Implement `SecurityConfig`**

```java
package com.haloticket.user_service.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtRoleConverter jwtRoleConverter;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**", "/health").permitAll()
                        .requestMatchers("/api/users/me").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtRoleConverter)))
                .build();
    }
}
```

- [ ] **Step 5: Run tests**

```powershell
cmd /c mvnw.cmd test -Dtest=JwtRoleConverterTest
```

Expected: pass.

- [ ] **Step 6: Commit user-service security**

```powershell
git add user-service\src\main\java\com\haloticket\user_service\config user-service\src\test\java\com\haloticket\user_service\config
git commit -m "feat: secure user service with keycloak jwt"
```

---

### Task 3: Backend user sync service

**Files:**
- Modify: `user-service/src/main/java/com/haloticket/user_service/dto/response/UserResponse.java`
- Create: `user-service/src/main/java/com/haloticket/user_service/dto/request/UserProfileUpdateRequest.java`
- Modify: `user-service/src/main/java/com/haloticket/user_service/service/UserService.java`
- Modify: `user-service/src/main/java/com/haloticket/user_service/service/impl/UserServiceImpl.java`
- Test: `user-service/src/test/java/com/haloticket/user_service/service/impl/UserServiceImplTest.java`

**Interfaces:**
- Consumes: `Jwt jwt`, `UserProfileUpdateRequest request`
- Produces: `UserResponse syncCurrentUser(Jwt jwt)`, `UserResponse updateCurrentUser(Jwt jwt, UserProfileUpdateRequest request)`

- [ ] **Step 1: Write failing service tests**

Create `UserServiceImplTest.java`:

```java
package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.config.JwtRoleConverter;
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserServiceImpl service = new UserServiceImpl(userRepository, new JwtRoleConverter());

    @Test
    void syncCurrentUserCreatesUserFromJwtWhenMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-123")
                .claim("email", "user@example.com")
                .claim("name", "Customer HaloTicket")
                .claim("roles", List.of("ROLE_USER"))
                .build();

        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.syncCurrentUser(jwt);

        assertThat(response.getKeycloakId()).isEqualTo("kc-123");
        assertThat(response.getEmail()).isEqualTo("user@example.com");
        assertThat(response.getFullName()).isEqualTo("Customer HaloTicket");
        assertThat(response.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(response.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.getIsActive()).isTrue();
    }

    @Test
    void updateCurrentUserOnlyUpdatesEditableProfileFields() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-123")
                .claim("email", "user@example.com")
                .claim("roles", List.of("ROLE_USER"))
                .build();
        User existing = User.builder()
                .keycloakId("kc-123")
                .email("user@example.com")
                .fullName("Old Name")
                .isActive(true)
                .status(UserStatus.ACTIVE)
                .roles(java.util.Set.of(Role.CUSTOMER))
                .build();
        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setFullName("New Name");
        request.setPhoneNumber("0900000000");
        request.setGender(Gender.MALE);
        request.setAvatarUrl("https://example.com/a.png");

        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.updateCurrentUser(jwt, request);

        assertThat(response.getFullName()).isEqualTo("New Name");
        assertThat(response.getPhoneNumber()).isEqualTo("0900000000");
        assertThat(response.getGender()).isEqualTo(Gender.MALE);
        assertThat(response.getAvatarUrl()).isEqualTo("https://example.com/a.png");
        verify(userRepository).save(existing);
    }
}
```

- [ ] **Step 2: Run failing tests**

```powershell
cmd /c mvnw.cmd test -Dtest=UserServiceImplTest
```

Expected: fail because new methods/classes are missing.

- [ ] **Step 3: Add `keycloakId` to response**

In `UserResponse.java`, add:

```java
private String keycloakId;
```

- [ ] **Step 4: Create profile update DTO**

```java
package com.haloticket.user_service.dto.request;

import com.haloticket.user_service.enums.Gender;
import lombok.Data;

@Data
public class UserProfileUpdateRequest {
    private String fullName;
    private String phoneNumber;
    private Gender gender;
    private String avatarUrl;
}
```

- [ ] **Step 5: Update service interface**

```java
package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import org.springframework.security.oauth2.jwt.Jwt;

public interface UserService {
    UserResponse createUser(UserCreateRequest request);
    UserResponse getUserByKeycloakId(String keycloakId);
    UserResponse syncCurrentUser(Jwt jwt);
    UserResponse updateCurrentUser(Jwt jwt, UserProfileUpdateRequest request);
}
```

- [ ] **Step 6: Implement service mapping**

In `UserServiceImpl`, use constructor injection for `UserRepository` and `JwtRoleConverter`. Implement:

```java
private UserResponse toResponse(User user) {
    return UserResponse.builder()
            .id(user.getId())
            .keycloakId(user.getKeycloakId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .phoneNumber(user.getPhoneNumber())
            .avatarUrl(user.getAvatarUrl())
            .isActive(user.getIsActive())
            .gender(user.getGender())
            .status(user.getStatus())
            .roles(user.getRoles())
            .createdAt(user.getCreatedAt())
            .build();
}
```

For `syncCurrentUser(Jwt jwt)`:

```java
String keycloakId = jwt.getSubject();
String email = jwt.getClaimAsString("email");
if (email == null || email.isBlank()) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Authenticated token does not contain email");
}

User user = userRepository.findByKeycloakId(keycloakId)
        .orElseGet(() -> {
            userRepository.findByEmail(email).ifPresent(conflict -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already belongs to another identity");
            });
            return User.builder()
                    .keycloakId(keycloakId)
                    .email(email)
                    .isActive(true)
                    .status(UserStatus.ACTIVE)
                    .build();
        });

user.setEmail(email);
user.setFullName(resolveName(jwt));
user.setRoles(jwtRoleConverter.toApplicationRoles(jwt));
user.setLastLoginAt(LocalDateTime.now());

return toResponse(userRepository.save(user));
```

Use helper `resolveName(Jwt jwt)`:

```java
String name = jwt.getClaimAsString("name");
if (name != null && !name.isBlank()) {
    return name;
}
String givenName = jwt.getClaimAsString("given_name");
String familyName = jwt.getClaimAsString("family_name");
String combined = Stream.of(givenName, familyName)
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.joining(" "));
if (!combined.isBlank()) {
    return combined;
}
return jwt.getClaimAsString("preferred_username");
```

- [ ] **Step 7: Run service tests**

```powershell
cmd /c mvnw.cmd test -Dtest=UserServiceImplTest
```

Expected: pass.

- [ ] **Step 8: Commit service sync**

```powershell
git add user-service\src\main\java\com\haloticket\user_service user-service\src\test\java\com\haloticket\user_service\service
git commit -m "feat: sync keycloak users into user database"
```

---

### Task 4: Backend `/api/users/me` endpoints

**Files:**
- Modify: `user-service/src/main/java/com/haloticket/user_service/controller/UserController.java`
- Test: `user-service/src/test/java/com/haloticket/user_service/controller/UserControllerTest.java`

**Interfaces:**
- Consumes: authenticated `JwtAuthenticationToken`
- Produces: `GET /api/users/me`, `PUT /api/users/me`

- [ ] **Step 1: Write failing controller test**

Create `UserControllerTest.java`:

```java
package com.haloticket.user_service.controller;

import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void getMeReturnsSyncedCurrentUser() throws Exception {
        when(userService.syncCurrentUser(any(Jwt.class))).thenReturn(UserResponse.builder()
                .id("internal-id")
                .keycloakId("kc-123")
                .email("user@example.com")
                .fullName("Customer HaloTicket")
                .isActive(true)
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.CUSTOMER))
                .build());

        mockMvc.perform(get("/api/users/me").with(jwt().jwt(token -> token.subject("kc-123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keycloakId").value("kc-123"))
                .andExpect(jsonPath("$.email").value("user@example.com"));
    }
}
```

- [ ] **Step 2: Run failing test**

```powershell
cmd /c mvnw.cmd test -Dtest=UserControllerTest
```

Expected: fail until `/me` exists and security test dependencies are correct.

- [ ] **Step 3: Add controller endpoints**

Add imports:

```java
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
```

Add methods above `/{keycloakId}` to avoid path ambiguity:

```java
@GetMapping("/me")
public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(userService.syncCurrentUser(jwt));
}

@PutMapping("/me")
public ResponseEntity<UserResponse> updateCurrentUser(
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody UserProfileUpdateRequest request
) {
    return ResponseEntity.ok(userService.updateCurrentUser(jwt, request));
}
```

- [ ] **Step 4: Run controller test**

```powershell
cmd /c mvnw.cmd test -Dtest=UserControllerTest
```

Expected: pass.

- [ ] **Step 5: Run all user-service tests**

```powershell
cmd /c mvnw.cmd test
```

from `user-service`.

Expected: pass.

- [ ] **Step 6: Commit current-user endpoints**

```powershell
git add user-service\src\main\java\com\haloticket\user_service\controller user-service\src\test\java\com\haloticket\user_service\controller
git commit -m "feat: expose current user profile api"
```

---

### Task 5: Gateway route authorization

**Files:**
- Modify: `api-gateway/src/main/java/com/haloticket/api_gateway/config/SecurityConfig.java`
- Test: `api-gateway/src/test/java/com/haloticket/api_gateway/ApiGatewayApplicationTests.java`

**Interfaces:**
- Consumes: Bearer JWT from web
- Produces: gateway denies unauthenticated protected API calls

- [ ] **Step 1: Record current insecure rules**

Run:

```powershell
Get-Content api-gateway\src\main\java\com\haloticket\api_gateway\config\SecurityConfig.java
```

Expected: `/api/users/**`, `/api/bookings/**`, and `/api/payments/**` are currently `permitAll`.

- [ ] **Step 2: Update authorization rules**

Replace the `authorizeExchange` block with:

```java
.authorizeExchange(exchange -> exchange
        .pathMatchers(HttpMethod.OPTIONS).permitAll()
        .pathMatchers("/eureka/**", "/actuator/**").permitAll()
        .pathMatchers("/api/events/**", "/api/tickets/**", "/api/marketplace/**", "/api/v1/**").permitAll()
        .pathMatchers("/api/users/me").authenticated()
        .pathMatchers("/api/users/**", "/api/bookings/**", "/api/payments/**", "/api/notifications/**").authenticated()
        .anyExchange().permitAll()
)
```

- [ ] **Step 3: Run gateway tests**

```powershell
cmd /c mvnw.cmd test
```

from `api-gateway`.

Expected: pass.

- [ ] **Step 4: Commit gateway security**

```powershell
git add api-gateway\src\main\java\com\haloticket\api_gateway\config\SecurityConfig.java api-gateway\src\test
git commit -m "feat: require jwt for protected gateway routes"
```

---

### Task 6: Web OIDC dependency and config

**Files:**
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\package.json`
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\package-lock.json`
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\.env.example`
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\config\index.ts`

**Interfaces:**
- Consumes: Vite env variables
- Produces: typed Keycloak config for auth module

- [ ] **Step 1: Install OIDC dependency**

Run from web repo:

```powershell
npm install oidc-client-ts
```

Expected: `package.json` and `package-lock.json` include `oidc-client-ts`.

- [ ] **Step 2: Update `.env.example`**

Set:

```env
VITE_API_URL=http://localhost:8888/api
VITE_KEYCLOAK_AUTHORITY=http://localhost:9080/realms/haloticket-realm
VITE_KEYCLOAK_CLIENT_ID=haloticket-web
VITE_KEYCLOAK_REDIRECT_URI=http://localhost:5173/auth/callback
VITE_KEYCLOAK_POST_LOGOUT_REDIRECT_URI=http://localhost:5173/
```

- [ ] **Step 3: Update `src/config/index.ts`**

Use:

```ts
export const CONFIG = {
  API_BASE_URL: import.meta.env.VITE_API_URL || "http://localhost:8888/api",
  TIMEOUT: 10000,
  KEYCLOAK: {
    AUTHORITY:
      import.meta.env.VITE_KEYCLOAK_AUTHORITY ||
      "http://localhost:9080/realms/haloticket-realm",
    CLIENT_ID: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || "haloticket-web",
    REDIRECT_URI:
      import.meta.env.VITE_KEYCLOAK_REDIRECT_URI ||
      `${window.location.origin}/auth/callback`,
    POST_LOGOUT_REDIRECT_URI:
      import.meta.env.VITE_KEYCLOAK_POST_LOGOUT_REDIRECT_URI ||
      window.location.origin,
  },
};
```

- [ ] **Step 4: Verify web compiles config**

```powershell
npm run build
```

Expected: pass.

- [ ] **Step 5: Commit web config**

```powershell
git add package.json package-lock.json .env.example src\config\index.ts
git commit -m "feat: add keycloak oidc config"
```

---

### Task 7: Web auth module

**Files:**
- Create: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\types.ts`
- Create: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\oidc.ts`
- Create: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\authStorage.ts`
- Create: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\authApi.ts`
- Create: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\AuthContext.tsx`

**Interfaces:**
- Consumes: `CONFIG.KEYCLOAK`, `/api/users/me`
- Produces: `AuthProvider`, `useAuth()`, `login`, `register`, `logout`, `refreshUser`

- [ ] **Step 1: Create auth types**

```ts
import type { Gender } from "@/types/common";

export type UserRole = "GUEST" | "CUSTOMER" | "ORGANIZER" | "STAFF" | "ADMIN";
export type UserStatus = "ACTIVE" | "INACTIVE" | "BANNED";

export interface CurrentUser {
  id: string;
  keycloakId: string;
  email: string;
  fullName: string | null;
  phoneNumber: string | null;
  avatarUrl: string | null;
  isActive: boolean;
  gender: Gender | null;
  status: UserStatus;
  roles: UserRole[];
  createdAt: string | null;
}
```

If `Gender` does not exist in `@/types/common`, define `export type Gender = "MALE" | "FEMALE" | "OTHER";` locally instead.

- [ ] **Step 2: Create token storage helpers**

```ts
export function persistAccessToken(token: string | null): void {
  if (token) {
    localStorage.setItem("access_token", token);
    return;
  }
  localStorage.removeItem("access_token");
}

export function readAccessToken(): string | null {
  const token = localStorage.getItem("access_token");
  return token && token !== "null" && token !== "undefined" ? token : null;
}
```

- [ ] **Step 3: Create OIDC user manager**

```ts
import { UserManager, WebStorageStateStore } from "oidc-client-ts";

import { CONFIG } from "@/config";

export const userManager = new UserManager({
  authority: CONFIG.KEYCLOAK.AUTHORITY,
  client_id: CONFIG.KEYCLOAK.CLIENT_ID,
  redirect_uri: CONFIG.KEYCLOAK.REDIRECT_URI,
  post_logout_redirect_uri: CONFIG.KEYCLOAK.POST_LOGOUT_REDIRECT_URI,
  response_type: "code",
  scope: "openid profile email",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.localStorage }),
});
```

- [ ] **Step 4: Create current user API**

```ts
import axiosClient from "@/services/axiosClient";
import type { CurrentUser } from "./types";

export function getCurrentUser(): Promise<CurrentUser> {
  return axiosClient.get("/users/me");
}
```

- [ ] **Step 5: Create `AuthContext`**

Implement a provider with this public interface:

```ts
interface AuthContextValue {
  isAuthenticated: boolean;
  isLoading: boolean;
  accessToken: string | null;
  user: CurrentUser | null;
  login: (returnTo?: string) => Promise<void>;
  register: (returnTo?: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}
```

Required behavior:

- On mount, call `userManager.getUser()`.
- If an OIDC user has `access_token`, persist it with `persistAccessToken`.
- Then call `getCurrentUser()` and set `user`.
- `login(returnTo)` calls `userManager.signinRedirect({ state: { returnTo } })`.
- `register(returnTo)` calls `userManager.signinRedirect({ state: { returnTo }, extraQueryParams: { screen_hint: "signup" } })`.
- `logout()` clears access token and calls `userManager.signoutRedirect()`.
- On catchable auth load failure, clear token and set `isLoading` false.

- [ ] **Step 6: Run TypeScript build**

```powershell
npm run build
```

Expected: pass after fixing import paths/types.

- [ ] **Step 7: Commit auth module**

```powershell
git add src\features\auth
git commit -m "feat: add keycloak auth provider"
```

---

### Task 8: Web callback, login, register, and protected routes

**Files:**
- Create: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\AuthCallback.tsx`
- Create: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\ProtectedRoute.tsx`
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\Login.tsx`
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\features\auth\Register.tsx`
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\routes\paths.ts`
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\routes\index.tsx`
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\main.tsx`

**Interfaces:**
- Consumes: `useAuth()`, `userManager.signinRedirectCallback()`
- Produces: route `/auth/callback`, protected route wrapper, login/register screens that redirect to Keycloak

- [ ] **Step 1: Add path**

In `paths.ts`, add:

```ts
AUTH_CALLBACK: "/auth/callback",
```

- [ ] **Step 2: Create callback page**

```tsx
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { userManager } from "./oidc";
import { persistAccessToken } from "./authStorage";
import { getCurrentUser } from "./authApi";

export default function AuthCallback() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let isMounted = true;

    async function completeLogin() {
      try {
        const oidcUser = await userManager.signinRedirectCallback();
        persistAccessToken(oidcUser.access_token);
        await getCurrentUser();
        const returnTo =
          typeof oidcUser.state === "object" &&
          oidcUser.state !== null &&
          "returnTo" in oidcUser.state &&
          typeof oidcUser.state.returnTo === "string"
            ? oidcUser.state.returnTo
            : "/";
        navigate(returnTo, { replace: true });
      } catch {
        if (isMounted) {
          setError("Khong the hoan tat dang nhap. Vui long thu lai.");
        }
      }
    }

    completeLogin();
    return () => {
      isMounted = false;
    };
  }, [navigate]);

  if (error) {
    return <div className="p-8 text-center app-text-main">{error}</div>;
  }

  return <div className="p-8 text-center app-text-main">Dang hoan tat dang nhap...</div>;
}
```

- [ ] **Step 3: Create protected route**

```tsx
import { Navigate, Outlet, useLocation } from "react-router-dom";

import { PATHS } from "@/routes/paths";
import { useAuth } from "./AuthContext";

export function ProtectedRoute() {
  const location = useLocation();
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return <div className="p-8 text-center app-text-main">Dang kiem tra phien dang nhap...</div>;
  }

  if (!isAuthenticated) {
    return <Navigate to={PATHS.LOGIN} replace state={{ returnTo: location.pathname }} />;
  }

  return <Outlet />;
}
```

- [ ] **Step 4: Replace login page**

`Login.tsx` should call `login` on button click and use router state `returnTo`:

```tsx
import { useLocation } from "react-router-dom";

import { Button } from "@/components/common/Button";
import { useAuth } from "./AuthContext";

export default function Login() {
  const location = useLocation();
  const { login, isLoading } = useAuth();
  const state = location.state as { returnTo?: string } | null;

  return (
    <div className="mx-auto flex min-h-[360px] max-w-md flex-col items-center justify-center gap-4 p-8 text-center">
      <h1 className="text-2xl font-bold app-text-main">Dang nhap HaloTicket</h1>
      <p className="app-text-muted">Tiep tuc bang tai khoan HaloTicket cua ban.</p>
      <Button onClick={() => login(state?.returnTo)} disabled={isLoading}>
        Dang nhap voi Keycloak
      </Button>
    </div>
  );
}
```

- [ ] **Step 5: Replace register page**

Use the same layout as login but call `register`.

- [ ] **Step 6: Wrap app with AuthProvider**

In `main.tsx`, wrap the router provider:

```tsx
<AuthProvider>
  <RouterProvider router={router} />
</AuthProvider>
```

- [ ] **Step 7: Wire routes**

In `routes/index.tsx`:

- Import `AuthCallback` and `ProtectedRoute`.
- Add `/auth/callback` as an auth route.
- Wrap `PATHS.CHECKOUT`, `PATHS.MY_TICKETS`, `PATHS.PROFILE`, organizer routes with `ProtectedRoute`.

Use this pattern:

```tsx
{
  element: <ProtectedRoute />,
  children: [
    { path: PATHS.CHECKOUT, element: <Checkout /> },
    { path: PATHS.MY_TICKETS, element: <MyTickets /> },
    { path: PATHS.PROFILE, element: <Profile /> },
  ],
}
```

- [ ] **Step 8: Build web**

```powershell
npm run build
```

Expected: pass.

- [ ] **Step 9: Commit routes**

```powershell
git add src\main.tsx src\routes src\features\auth
git commit -m "feat: wire keycloak login routes"
```

---

### Task 9: Web header auth controls

**Files:**
- Modify: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\src\components\Header.tsx`

**Interfaces:**
- Consumes: `useAuth()`
- Produces: login/register links when anonymous; user label and logout action when authenticated

- [ ] **Step 1: Inspect current header**

```powershell
Get-Content src\components\Header.tsx
```

Expected: identify current login/register buttons or profile link locations.

- [ ] **Step 2: Update header behavior**

Use this logic inside the existing visual structure:

```tsx
const { isAuthenticated, user, logout } = useAuth();
```

Render:

- Anonymous: links to `PATHS.LOGIN` and `PATHS.REGISTER`.
- Authenticated: display `user?.fullName || user?.email || "Tai khoan"` and a logout button.

Keep existing token-backed styling from `DESIGN.md`; do not introduce hardcoded brand hex colors.

- [ ] **Step 3: Build web**

```powershell
npm run build
```

Expected: pass.

- [ ] **Step 4: Commit header auth UI**

```powershell
git add src\components\Header.tsx
git commit -m "feat: show authenticated user in header"
```

---

### Task 10: End-to-end local verification

**Files:**
- Read: `D:\HaloTicket\HaloTicket_Repo\haloticket-backend\docker-compose.yml`
- Read: `D:\HaloTicket\HaloTicket_Repo\haloticket-backend\haloticket-infrastructure\keycloak\realm-export.json`
- Read: `D:\HaloTicket\HaloTicket_Repo\haloticket-web\.env.example`

**Interfaces:**
- Consumes: Docker infrastructure, backend services, Vite web app
- Produces: verified login/register/logout and DB sync behavior

- [ ] **Step 1: Start infrastructure**

From backend repo:

```powershell
docker compose up -d postgres keycloak
```

Expected: containers `haloticket-postgres` and `haloticket-keycloak` healthy.

- [ ] **Step 2: Start discovery, gateway, and user-service**

Open separate terminals:

```powershell
cd discovery-server
cmd /c mvnw.cmd spring-boot:run
```

```powershell
cd user-service
cmd /c mvnw.cmd spring-boot:run
```

```powershell
cd api-gateway
cmd /c mvnw.cmd spring-boot:run
```

Expected ports:

- Discovery server: `8761`
- User service: `8081`
- Gateway: `8888`

- [ ] **Step 3: Start web**

From web repo:

```powershell
npm run dev
```

Expected: Vite serves on `http://localhost:5173`.

- [ ] **Step 4: Verify login**

In browser:

1. Open `http://localhost:5173/login`.
2. Login with seed user:
   - Username: `user`
   - Password: `user123`
3. Confirm browser returns to web.
4. Confirm protected route `/profile` loads.

- [ ] **Step 5: Verify DB sync**

Run:

```powershell
docker exec -it haloticket-postgres psql -U haloticket -d user_db -c "select keycloak_id,email,full_name,status from users;"
```

Expected: one row exists for `user@haloticket.com`.

- [ ] **Step 6: Verify no duplicate on second login**

Logout, login again, then run:

```powershell
docker exec -it haloticket-postgres psql -U haloticket -d user_db -c "select email,count(*) from users group by email;"
```

Expected: `user@haloticket.com` count is `1`.

- [ ] **Step 7: Verify protected route redirect**

Clear browser storage or logout, then open:

```text
http://localhost:5173/profile
```

Expected: redirected to `/login`; after login, returned to `/profile`.

- [ ] **Step 8: Final automated checks**

Backend:

```powershell
cd D:\HaloTicket\HaloTicket_Repo\haloticket-backend\user-service
cmd /c mvnw.cmd test
cd D:\HaloTicket\HaloTicket_Repo\haloticket-backend\api-gateway
cmd /c mvnw.cmd test
```

Web:

```powershell
cd D:\HaloTicket\HaloTicket_Repo\haloticket-web
npm run build
```

Expected: all pass.

- [ ] **Step 9: Commit verification docs or final fixes**

If verification required code fixes:

```powershell
git status --short
git add <changed-files>
git commit -m "fix: complete keycloak auth verification"
```

If no files changed, skip commit.

---

### Task 11: Registration challenge, password policy, and OTP persistence

**Files:**
- Create migration under `user-service/src/main/resources/db/migration`: `auth_otp_challenges` and indexes/constraints.
- Create `AuthOtpChallenge` entity, repository, purpose/status enums.
- Create `PasswordPolicyValidator`, `OtpGenerator`, `OtpHasher`, `RateLimitService`.

**Implementation details:**
- Enforce length 8-32, lowercase, digit, special character and uppercase; return a field-level error map so the web can render the four checklist rows.
- Generate exactly six digits with `SecureRandom`; persist only a one-way hash/HMAC with a server-side pepper from secret manager.
- Challenge states must allow only `PENDING -> VERIFIED -> CONSUMED/EXPIRED/LOCKED` using a transaction and optimistic locking.
- Store `expiresAt`, `resendAvailableAt`, `sendCount`, `failedAttempts`, purpose and masked/audited request metadata.
- Prefer Redis for IP/email quotas, with DB challenge state as durable source. Defaults: 5 minutes TTL, 60 seconds cooldown, 5 sends per registration/15 minutes, 10 requests per email/IP/hour, 5 failed OTP attempts.

- [ ] **Step 1: Write failing tests** for each password rule, mismatch, OTP length/randomness, expiry, single-use, cooldown, max sends and max attempts.
- [ ] **Step 2: Implement migration and domain services.** Never log OTP, password or reset token.
- [ ] **Step 3: Run focused tests.** `cmd /c mvnw.cmd test -Dtest=PasswordPolicyValidatorTest,OtpServiceTest,RateLimitServiceTest`
- [ ] **Step 4: Commit:** `feat: add auth otp challenge domain`

### Task 12: Registration API and Keycloak provisioning

**Files:**
- Create `AuthRegistrationController`, `AuthRegistrationService`, request/response DTOs.
- Create `KeycloakAdminClient` using a confidential service account and `OtpEmailSender` port/adapter.
- Modify gateway public route allow-list only for `/api/auth/registrations/**`.

**API behavior:**
- `POST /api/auth/registrations` validates input, normalizes email/phone, creates challenge and sends OTP; response `202` with opaque `challengeId`, `expiresAt`, `resendAvailableAt`.
- `POST /api/auth/registrations/{id}/resend` checks cooldown and quotas, invalidates prior OTP and sends a new one.
- `POST /api/auth/registrations/{id}/verify` consumes the OTP atomically, then creates Keycloak user (`emailVerified=true`) and local `users` row in one compensating workflow. If DB persistence fails after Keycloak creation, retry/reconcile by Keycloak ID; never create a duplicate.
- Do not reveal whether an email already exists in public registration responses. Log only event type, challenge ID and correlation ID.

- [ ] **Step 1: Write MockMvc/service tests** for 202 response, invalid password, mismatch, resend limits, expired OTP and duplicate provisioning.
- [ ] **Step 2: Implement API and Keycloak adapter.** Keep Admin API credentials server-side; password exists only in bounded request memory.
- [ ] **Step 3: Run tests:** `cmd /c mvnw.cmd test -Dtest=AuthRegistrationControllerTest,AuthRegistrationServiceTest`
- [ ] **Step 4: Commit:** `feat: implement email otp registration`

### Task 13: Forgot-password API with email and phone verification

**Files:**
- Create `PasswordResetController`, `PasswordResetService`, reset DTOs and tests.
- Reuse OTP challenge, policy, sender and rate-limit components from Tasks 11-12.
- Extend `KeycloakAdminClient` with reset credential and optional session revocation.

**API behavior:**
- `POST /api/auth/password-resets` accepts email + phone and always returns a generic `202` response for syntactically valid input. Only send OTP when both values match the same local/Keycloak account.
- `POST /api/auth/password-resets/{id}/verify` returns a one-time opaque reset token after OTP verification; store only its hash, TTL 10 minutes.
- `POST /api/auth/password-resets/{id}/complete` revalidates reset token and new password policy, calls Keycloak reset credential, consumes the token, and returns `204`.
- Reset must be single-use, revoke existing sessions according to the security decision, and never auto-login the user.

- [ ] **Step 1: Write tests** for email/phone mismatch, generic response, OTP lifecycle, reset token replay and new-password policy.
- [ ] **Step 2: Implement controller/service and Keycloak reset call.** Use constant-time comparisons where applicable.
- [ ] **Step 3: Run:** `cmd /c mvnw.cmd test -Dtest=PasswordResetControllerTest,PasswordResetServiceTest`
- [ ] **Step 4: Commit:** `feat: implement otp password reset`

### Task 14: Web registration and forgot-password UX

**Files:**
- Create `src/features/auth/registrationApi.ts`, `passwordResetApi.ts`, challenge state hooks/components.
- Replace placeholder `Register.tsx` and extend `Login.tsx` with forgot-password entry/form.
- Add routes `/register/verify`, `/forgot-password`, `/forgot-password/verify`, `/forgot-password/new-password` as needed by the chosen router structure.

**Required states:**
- Registration: form, password checklist, submit loading, OTP input, server-driven countdown, resend cooldown/quota, success and retry errors.
- Reset: email + phone form, OTP form, new-password + confirm form, success redirect to login.
- Password values stay in React memory only; never localStorage, query string, analytics or error text.
- OTP input accepts six digits, paste, keyboard navigation and screen-reader labels. The attached image informs visual states only; Google/Cloudflare controls are excluded unless separately configured.

- [ ] **Step 1: Add API/types and component tests** for every state and validation rule.
- [ ] **Step 2: Implement forms using existing web components and Axios client.** Map `400/409/429` error codes to Vietnamese messages.
- [ ] **Step 3: Run `npm run build` and relevant frontend tests.**
- [ ] **Step 4: Commit:** `feat: add registration and password reset flows`

### Task 15: End-to-end security and acceptance verification

- [ ] Start Postgres, Redis, Keycloak and an SMTP test sink; configure Keycloak Admin service account and mail sender through environment secrets.
- [ ] Verify registration success creates exactly one Keycloak account and one `users` record only after correct OTP.
- [ ] Verify wrong/expired/replayed OTP, cooldown, send quota, IP/email quota and failed-attempt lockout.
- [ ] Verify forgot password requires email + phone match, returns generic response for unknown users, resets Keycloak password, invalidates reset token and old sessions as configured.
- [ ] Verify old OIDC login/protected routes, `GET /api/users/me`, logout and no duplicate sync still pass.
- [ ] Run backend focused tests, gateway tests and `npm run build`; record results in the PR.

## Execution Notes

- Implement backend tasks first because web callback depends on `/api/users/me`.
- Keep backend and web commits separate because they live in separate git repositories.
- If `npm install oidc-client-ts` needs network access, request approval for that command.
- If Spring Security package names differ under Spring Boot 4.1.1, prefer compiler-guided fixes while preserving the public interfaces in this plan.
- Do not change Keycloak client IDs unless realm export and web env are updated together.
