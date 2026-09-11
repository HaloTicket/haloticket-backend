package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.entity.EkycAttempt;
import com.haloticket.user_service.entity.EkycProfile;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.EkycAttemptStatus;
import com.haloticket.user_service.enums.EkycVerificationStatus;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.EkycAttemptRepository;
import com.haloticket.user_service.repository.EkycProfileRepository;
import com.haloticket.user_service.repository.UserRepository;
import com.haloticket.user_service.service.EkycDecisionPolicy;
import com.haloticket.user_service.service.EkycDtos;
import com.haloticket.user_service.service.EkycService;
import com.haloticket.user_service.service.IdentityFingerprint;
import com.haloticket.user_service.service.VnptEkycClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.io.IOException;
import java.util.HashSet;
import javax.imageio.ImageIO;

@Service
public class EkycServiceImpl implements EkycService {
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

    private final UserRepository userRepository;
    private final EkycAttemptRepository attemptRepository;
    private final EkycProfileRepository profileRepository;
    private final VnptEkycClient vnptClient;
    private final EkycDecisionPolicy decisionPolicy;
    private final IdentityFingerprint identityFingerprint;
    private final Clock clock;

    public EkycServiceImpl(
            UserRepository userRepository,
            EkycAttemptRepository attemptRepository,
            EkycProfileRepository profileRepository,
            VnptEkycClient vnptClient,
            EkycDecisionPolicy decisionPolicy,
            IdentityFingerprint identityFingerprint,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.attemptRepository = attemptRepository;
        this.profileRepository = profileRepository;
        this.vnptClient = vnptClient;
        this.decisionPolicy = decisionPolicy;
        this.identityFingerprint = identityFingerprint;
        this.clock = clock;
    }

    @Override
    @Transactional
    public EkycDtos.SessionResponse startSession(
            String keycloakId,
            boolean consentAccepted,
            String consentVersion,
            String idempotencyKey
    ) {
        User user = requireUserForUpdate(keycloakId);
        if (!consentAccepted || !"2026.1".equals(consentVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED");
        }
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED");
        }
        return attemptRepository.findFirstByUserIdAndIdempotencyKey(user.getId(), idempotencyKey)
                .map(this::toSession)
                .orElseGet(() -> {
                    identityFingerprint.requireConfigured();
                    vnptClient.requireReady();
                    if (attemptRepository.countByUserIdAndCreatedAtAfter(user.getId(), clock.instant().minusSeconds(86400)) >= 5) {
                        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "EKYC_ATTEMPT_LIMIT");
                    }
                    EkycAttempt attempt = EkycAttempt.builder()
                            .user(user)
                            .idempotencyKey(idempotencyKey)
                            .consentVersion(consentVersion)
                            .status(EkycAttemptStatus.CREATED)
                            .createdAt(clock.instant())
                            .expiresAt(clock.instant().plusSeconds(15 * 60))
                            .build();
                    return toSession(attemptRepository.save(attempt));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public EkycDtos.StatusResponse getStatus(String keycloakId) {
        User user = requireUser(keycloakId);
        return profileRepository.findByUserId(user.getId())
                .map(profile -> toStatus(profile, null))
                .orElseGet(() -> attemptRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                        .map(attempt -> new EkycDtos.StatusResponse(
                                attempt.getStatus() == EkycAttemptStatus.MANUAL_REVIEW ? EkycVerificationStatus.PENDING
                                        : attempt.getStatus() == EkycAttemptStatus.FAILED ? EkycVerificationStatus.REJECTED
                                        : EkycVerificationStatus.NOT_STARTED,
                                null, null, null, null, attempt.getFailureReason()))
                        .orElse(new EkycDtos.StatusResponse(EkycVerificationStatus.NOT_STARTED, null, null, null, null, "EKYC_NOT_STARTED")));
    }

    @Override
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public EkycDtos.StatusResponse verify(
            String keycloakId,
            String sessionId,
            MultipartFile frontImage,
            MultipartFile backImage,
            MultipartFile faceImage
    ) {
        // Serialize session consumption for this user; HTTP calls have bounded timeouts.
        User user = requireUserForUpdate(keycloakId);
        EkycAttempt attempt = attemptRepository.findById(sessionId)
                .filter(value -> value.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EKYC_SESSION_NOT_FOUND"));
        if (attempt.getStatus() != EkycAttemptStatus.CREATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EKYC_SESSION_ALREADY_PROCESSED");
        }
        if (!attempt.getExpiresAt().isAfter(clock.instant())) {
            attempt.setStatus(EkycAttemptStatus.EXPIRED);
            attemptRepository.save(attempt);
            throw new ResponseStatusException(HttpStatus.GONE, "EKYC_SESSION_EXPIRED");
        }
        validateImage(frontImage, "FRONT_IMAGE_INVALID");
        validateImage(backImage, "BACK_IMAGE_INVALID");
        validateImage(faceImage, "FACE_IMAGE_INVALID");
        identityFingerprint.requireConfigured();
        vnptClient.requireReady();

        attempt.setStatus(EkycAttemptStatus.PROCESSING);
        attemptRepository.save(attempt);

        VnptEkycClient.VerificationResult providerResult;
        try {
            providerResult = vnptClient.verify(frontImage, backImage, faceImage);
        } catch (RuntimeException exception) {
            attempt.setStatus(EkycAttemptStatus.ERROR);
            attempt.setFailureReason("PROVIDER_UNAVAILABLE");
            attemptRepository.save(attempt);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_PROVIDER_UNAVAILABLE");
        }

        EkycDecisionPolicy.Decision decision = decisionPolicy.evaluate(providerResult);
        if (providerResult != null) {
            attempt.setProviderReference(providerResult.providerReference());
            if (Double.isFinite(providerResult.matchScore())) attempt.setMatchScore(providerResult.matchScore());
        }
        if (!decision.passed()) {
            attempt.setStatus(EkycAttemptStatus.FAILED);
            attempt.setFailureReason(decision.reasonCode());
            attemptRepository.save(attempt);
            return new EkycDtos.StatusResponse(
                    EkycVerificationStatus.REJECTED, null, null, null, null, decision.reasonCode()
            );
        }

        if (providerResult.documentExpiresOn() == null || providerResult.documentExpiresOn().isBefore(LocalDate.now(clock))) {
            attempt.setStatus(EkycAttemptStatus.FAILED);
            attempt.setFailureReason("DOCUMENT_EXPIRED");
            attemptRepository.save(attempt);
            return new EkycDtos.StatusResponse(EkycVerificationStatus.REJECTED, null, null, null, null, "DOCUMENT_EXPIRED");
        }

        String fingerprint = identityFingerprint.create(providerResult.documentNumber());
        EkycProfile profile = profileRepository.findByUserId(user.getId()).orElseGet(EkycProfile::new);
        if (profileRepository.existsByDocumentNumberHashAndUserIdNot(fingerprint, user.getId())) {
            attempt.setStatus(EkycAttemptStatus.FAILED);
            attempt.setFailureReason("DUPLICATE_IDENTITY");
            attemptRepository.save(attempt);
            return new EkycDtos.StatusResponse(
                    EkycVerificationStatus.REJECTED, null, null, null, null, "DUPLICATE_IDENTITY"
            );
        }

        profile.setUser(user);
        profile.setDocumentType(providerResult.documentType());
        profile.setDocumentNumber(mask(providerResult.documentNumber()));
        profile.setDocumentNumberHash(fingerprint);
        profile.setLegalName(providerResult.legalName().trim());
        profile.setStatus(EkycVerificationStatus.APPROVED);
        profile.setApprovedAt(clock.instant().atZone(ZoneOffset.UTC).toLocalDateTime());
        profile.setDocumentExpiresOn(providerResult.documentExpiresOn());
        profile.setAcceptedAttempt(attempt);
        profile.setIdentityVersion(profile.getIdentityVersion() == null ? 1L : profile.getIdentityVersion() + 1L);
        profile = profileRepository.save(profile);

        attempt.setStatus(EkycAttemptStatus.PASSED);
        attempt.setFailureReason(null);
        attemptRepository.save(attempt);
        if (user.getRoles() == null) user.setRoles(new HashSet<>());
        user.getRoles().add(Role.ORGANIZER);
        userRepository.save(user);
        return toStatus(profile, null);
    }

    @Override
    @Transactional(readOnly = true)
    public EkycDtos.OrganizerEligibility getOrganizerEligibility(String keycloakId) {
        User user = requireUser(keycloakId);
        return profileRepository.findByUserId(user.getId())
                .filter(this::isVerified)
                .map(profile -> new EkycDtos.OrganizerEligibility(
                        true, user.getId(), profile.getIdentityVersion(), "ELIGIBLE"
                ))
                .orElse(new EkycDtos.OrganizerEligibility(
                        false, user.getId(), null, "EKYC_NOT_STARTED"
                ));
    }

    private User requireUser(String keycloakId) {
        return userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private User requireUserForUpdate(String keycloakId) {
        User user = userRepository.findForEkycUpdateByKeycloakId(keycloakId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "USER_INACTIVE");
        }
        return user;
    }

    private boolean isVerified(EkycProfile profile) {
        return Boolean.TRUE.equals(profile.getUser().getIsActive())
                && profile.getUser().getStatus() == UserStatus.ACTIVE
                && profile.getStatus() == EkycVerificationStatus.APPROVED
                && profile.getAcceptedAttempt() != null && profile.getAcceptedAttempt().getStatus() == EkycAttemptStatus.PASSED
                && profile.getIdentityVersion() != null && profile.getIdentityVersion() > 0
                && profile.getDocumentNumberHash() != null && profile.getApprovedAt() != null
                && profile.getDocumentExpiresOn() != null && !profile.getDocumentExpiresOn().isBefore(LocalDate.now(clock));
    }

    private void validateImage(MultipartFile file, String reason) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_IMAGE_SIZE
                || !("image/jpeg".equals(file.getContentType()) || "image/png".equals(file.getContentType()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }
        try (var stream = ImageIO.createImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported image");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                if (reader.getWidth(0) < 100 || reader.getHeight(0) < 100
                        || (long) reader.getWidth(0) * reader.getHeight(0) > 25_000_000) {
                    throw new IOException("Invalid image dimensions");
                }
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }
    }

    private String mask(String number) {
        String normalized = number.replaceAll("\\s+", "");
        int visible = Math.min(4, normalized.length());
        return "*".repeat(Math.max(0, normalized.length() - visible)) + normalized.substring(normalized.length() - visible);
    }

    private EkycDtos.SessionResponse toSession(EkycAttempt attempt) {
        return new EkycDtos.SessionResponse(attempt.getId(), attempt.getStatus(), attempt.getExpiresAt());
    }

    private EkycDtos.StatusResponse toStatus(EkycProfile profile, String reason) {
        Instant verifiedAt = profile.getApprovedAt() == null
                ? null
                : profile.getApprovedAt().toInstant(ZoneOffset.UTC);
        return new EkycDtos.StatusResponse(
                profile.getStatus() == EkycVerificationStatus.APPROVED && !isVerified(profile)
                        ? EkycVerificationStatus.REQUIRES_UPDATE : profile.getStatus(),
                profile.getLegalName(),
                profile.getDocumentNumber(),
                verifiedAt,
                profile.getIdentityVersion(),
                reason
        );
    }
}
