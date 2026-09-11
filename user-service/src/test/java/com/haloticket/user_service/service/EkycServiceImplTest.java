package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.response.EkycOrganizerEligibilityResponse;
import com.haloticket.user_service.dto.response.EkycSessionResponse;
import com.haloticket.user_service.dto.response.EkycStatusResponse;
import com.haloticket.user_service.entity.EkycAttempt;
import com.haloticket.user_service.entity.EkycProfile;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.EkycAttemptStatus;
import com.haloticket.user_service.enums.EkycDocumentType;
import com.haloticket.user_service.enums.EkycVerificationStatus;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.EkycAttemptRepository;
import com.haloticket.user_service.repository.EkycProfileRepository;
import com.haloticket.user_service.repository.UserRepository;
import com.haloticket.user_service.service.impl.EkycServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EkycServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

    @Mock UserRepository userRepository;
    @Mock EkycAttemptRepository attemptRepository;
    @Mock EkycProfileRepository profileRepository;
    @Mock VnptEkycClient vnptClient;

    private EkycService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new EkycServiceImpl(
                userRepository,
                attemptRepository,
                profileRepository,
                vnptClient,
                new EkycDecisionPolicy(),
                new IdentityFingerprint("test-secret-at-least-16-bytes"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        user = User.builder()
                .id("user-1")
                .keycloakId("kc-1")
                .isActive(true)
                .status(UserStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(Role.CUSTOMER)))
                .build();
    }

    @Test
    void startSessionRequiresConsent() {
        when(userRepository.findForEkycUpdateByKeycloakId("kc-1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.startSession("kc-1", false, "2026.1", "idem-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CONSENT_REQUIRED");
    }

    @Test
    void startSessionCreatesExpiringAttemptBoundToAuthenticatedUser() {
        when(userRepository.findForEkycUpdateByKeycloakId("kc-1")).thenReturn(Optional.of(user));
        when(attemptRepository.findFirstByUserIdAndIdempotencyKey("user-1", "idem-1"))
                .thenReturn(Optional.empty());
        when(attemptRepository.save(any())).thenAnswer(invocation -> {
            EkycAttempt attempt = invocation.getArgument(0);
            attempt.setId("attempt-1");
            return attempt;
        });

        EkycSessionResponse result = service.startSession("kc-1", true, "2026.1", "idem-1");

        assertThat(result.sessionId()).isEqualTo("attempt-1");
        assertThat(result.status()).isEqualTo(EkycAttemptStatus.CREATED);
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
        ArgumentCaptor<EkycAttempt> captor = ArgumentCaptor.forClass(EkycAttempt.class);
        verify(attemptRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getConsentVersion()).isEqualTo("2026.1");
    }

    @Test
    void startSessionRejectsAlreadyVerifiedAccount() {
        when(userRepository.findForEkycUpdateByKeycloakId("kc-1")).thenReturn(Optional.of(user));
        EkycProfile profile = EkycProfile.builder().user(user).status(EkycVerificationStatus.APPROVED).build();
        when(profileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.startSession("kc-1", true, "2026.1", "idem-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("EKYC_ALREADY_VERIFIED");
    }

    @Test
    void verifyApprovesOnlyWhenAllProviderChecksPass() {
        EkycAttempt attempt = EkycAttempt.builder()
                .id("attempt-1")
                .user(user)
                .status(EkycAttemptStatus.CREATED)
                .expiresAt(NOW.plusSeconds(60))
                .build();
        when(userRepository.findForEkycUpdateByKeycloakId("kc-1")).thenReturn(Optional.of(user));
        when(attemptRepository.findById("attempt-1")).thenReturn(Optional.of(attempt));
        when(profileRepository.findByUserId("user-1")).thenReturn(Optional.empty());
        when(vnptClient.verify(any(), any())).thenReturn(new VnptEkycClient.VerificationResult(
                "vnpt-123", EkycDocumentType.ID_CARD, "079123456789", "NGUYEN VAN A",
                true, true, true, true, 0.97, LocalDate.of(2036, 1, 1)
        ));
        when(profileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EkycStatusResponse result = service.verify(
                "kc-1",
                "attempt-1",
                file("front"),
                file("back")
        );

        assertThat(result.status()).isEqualTo(EkycVerificationStatus.APPROVED);
        assertThat(user.getRoles()).contains(Role.ORGANIZER);
        ArgumentCaptor<EkycProfile> profile = ArgumentCaptor.forClass(EkycProfile.class);
        verify(profileRepository).save(profile.capture());
        assertThat(profile.getValue().getDocumentNumber()).isNotEqualTo("079123456789");
        assertThat(profile.getValue().getDocumentNumber()).startsWith("******");
        assertThat(profile.getValue().getApprovedAt()).isNotNull();
    }

    @Test
    void verifyRejectsAttemptOwnedByAnotherUserBeforeCallingProvider() {
        User other = User.builder().id("user-2").keycloakId("kc-2").build();
        EkycAttempt attempt = EkycAttempt.builder()
                .id("attempt-1")
                .user(other)
                .status(EkycAttemptStatus.CREATED)
                .expiresAt(NOW.plusSeconds(60))
                .build();
        when(userRepository.findForEkycUpdateByKeycloakId("kc-1")).thenReturn(Optional.of(user));
        when(attemptRepository.findById("attempt-1")).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.verify("kc-1", "attempt-1", file("front"), file("back")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("EKYC_SESSION_NOT_FOUND");
    }

    @Test
    void verifyReturnsRejectedStatusWhenProviderRejectsDocument() {
        EkycAttempt attempt = EkycAttempt.builder()
                .id("attempt-1")
                .user(user)
                .status(EkycAttemptStatus.CREATED)
                .expiresAt(NOW.plusSeconds(60))
                .build();
        when(userRepository.findForEkycUpdateByKeycloakId("kc-1")).thenReturn(Optional.of(user));
        when(attemptRepository.findById("attempt-1")).thenReturn(Optional.of(attempt));
        when(vnptClient.verify(any(), any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad request", null,
                        "{\"message\":\"IDG-00010445\",\"errors\":[\"Loại giấy tờ không hợp lệ\"]}".getBytes(), null)
        );

        EkycStatusResponse result = service.verify(
                "kc-1", "attempt-1", file("front"), file("back")
        );

        assertThat(result.status()).isEqualTo(EkycVerificationStatus.REJECTED);
        assertThat(result.reasonCode()).isEqualTo("DOCUMENT_TYPE_INVALID");
        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.FAILED);
    }

    @Test
    void verifyDoesNotExpireAnAlreadyCompletedAttempt() {
        EkycAttempt attempt = EkycAttempt.builder()
                .id("attempt-1")
                .user(user)
                .status(EkycAttemptStatus.PASSED)
                .expiresAt(NOW.minusSeconds(1))
                .build();
        when(userRepository.findForEkycUpdateByKeycloakId("kc-1")).thenReturn(Optional.of(user));
        when(attemptRepository.findById("attempt-1")).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.verify("kc-1", "attempt-1", file("front"), file("back")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("EKYC_SESSION_ALREADY_PROCESSED");

        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.PASSED);
        verify(attemptRepository, never()).save(attempt);
    }

    @Test
    void organizerEligibilityNeverTreatsMissingProfileAsEligible() {
        when(userRepository.findByKeycloakId("kc-1")).thenReturn(Optional.of(user));
        when(profileRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        EkycOrganizerEligibilityResponse result = service.getOrganizerEligibility("kc-1");

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("EKYC_NOT_STARTED");
        assertThat(result.userId()).isEqualTo("user-1");
    }

    private MockMultipartFile file(String name) {
        try {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "jpg", bytes);
            return new MockMultipartFile(name, name + ".jpg", "image/jpeg", bytes.toByteArray());
        } catch (IOException ex) { throw new RuntimeException(ex); }
    }
}
