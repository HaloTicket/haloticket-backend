package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.entity.AuthOtpChallenge;
import com.haloticket.user_service.enums.OtpStatus;
import com.haloticket.user_service.repository.AuthOtpChallengeRepository;
import com.haloticket.user_service.service.AuthOtpChallengeService;
import com.haloticket.user_service.service.OtpChallengeCommand;
import com.haloticket.user_service.service.OtpChallengePersistence;
import com.haloticket.user_service.service.OtpGenerator;
import com.haloticket.user_service.service.OtpHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import jakarta.persistence.OptimisticLockException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Cài đặt vòng đời OTP: hạn dùng 5 phút, chờ 60 giây giữa hai lần gửi, tối đa 5 lần gửi mỗi 15 phút và 5 lần nhập sai. */
@Service
public class OtpChallengeServiceImpl implements AuthOtpChallengeService {
    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final Duration SEND_WINDOW = Duration.ofMinutes(15);
    static final int MAX_SENDS = 5;
    static final int MAX_ATTEMPTS = 5;

    private final AuthOtpChallengeRepository repository;
    private final OtpGenerator generator;
    private final OtpHasher hasher;
    private final Clock clock;
    private final OtpChallengePersistence persistence;

    /** Tạo service với đầy đủ thành phần phụ thuộc, dùng cho Spring. */
    @org.springframework.beans.factory.annotation.Autowired
    public OtpChallengeServiceImpl(AuthOtpChallengeRepository repository, OtpGenerator generator,
                                   OtpHasher hasher, Clock clock, OtpChallengePersistence persistence) {
        this.repository = repository;
        this.generator = generator;
        this.hasher = hasher;
        this.clock = clock;
        this.persistence = persistence;
    }

    /** Tạo service với {@link OtpChallengePersistence} mặc định dựng từ kho lưu, tiện cho kiểm thử. */
    public OtpChallengeServiceImpl(AuthOtpChallengeRepository repository, OtpGenerator generator,
                                   OtpHasher hasher, Clock clock) {
        this(repository, generator, hasher, clock, new OtpChallengePersistence(repository));
    }

    /**
     * Tạo thử thách mới và sinh OTP. Nếu cùng khóa idempotency đã có thử thách còn hạn thì trả lại thử thách đó (không có mã chữ rõ);
     * nếu thử thách cũ đã hết hạn hoặc không còn chờ thì báo dùng lại khóa.
     *
     * @throws AuthOtpChallengeService.IdempotencyKeyReuseException khi khóa idempotency không thể dùng lại
     */
    @Override
    public CreateResult create(OtpChallengeCommand command) {
        Instant now = clock.instant();
        return repository.findByIdempotencyKeyAndPurposeAndSubject(
                        command.idempotencyKey(), command.purpose(), command.subject())
                .map(challenge -> {
                    if (challenge.getStatus() != OtpStatus.PENDING
                            || challenge.getExpiresAt() == null
                            || !now.isBefore(challenge.getExpiresAt())) {
                        if (challenge.getStatus() == OtpStatus.PENDING
                            && (challenge.getExpiresAt() == null || !now.isBefore(challenge.getExpiresAt()))) {
                            challenge.setStatus(OtpStatus.EXPIRED);
                            persistence.markExpired(challenge);
                        }
                        throw new AuthOtpChallengeService.IdempotencyKeyReuseException();
                    }
                    return new CreateResult(challenge, null);
                })
                .orElseGet(() -> createNew(command, now));
    }

    /** Lưu thử thách mới; nếu hai yêu cầu cùng tạo một lúc thì dùng thử thách của yêu cầu thắng cuộc. */
    private CreateResult createNew(OtpChallengeCommand command, Instant now) {
        String otp = generator.generate();
        AuthOtpChallenge challenge = AuthOtpChallenge.builder()
                .purpose(command.purpose()).subject(command.subject())
                .idempotencyKey(command.idempotencyKey()).requestIpHash(command.requestIpHash())
                .otpHash(hasher.hash(otp)).status(OtpStatus.PENDING)
                .expiresAt(now.plus(TTL)).resendAvailableAt(now.plus(RESEND_COOLDOWN))
                .sendWindowStartedAt(now).sendCount(1).failedAttempts(0).build();
        try {
            return new CreateResult(persistence.insert(challenge), otp);
        } catch (DataIntegrityViolationException exception) {
            // Lần chèn thất bại đã được rollback trong giao dịch REQUIRES_NEW của nó.
            AuthOtpChallenge winner = repository.findByIdempotencyKeyAndPurposeAndSubject(
                            command.idempotencyKey(), command.purpose(), command.subject())
                    .orElseThrow(() -> exception);
            if (winner.getStatus() != OtpStatus.PENDING || winner.getExpiresAt() == null
                    || !now.isBefore(winner.getExpiresAt())) {
                throw new AuthOtpChallengeService.IdempotencyKeyReuseException();
            }
            return new CreateResult(winner, null);
        }
    }

    /**
     * Gửi lại OTP: chỉ khi thử thách còn chờ và đã qua thời gian chờ, đặt lại cửa sổ đếm khi cửa sổ cũ hết, sinh mã mới và gia hạn.
     *
     * @throws AuthOtpChallengeService.InvalidStateException khi thử thách không còn chờ
     * @throws AuthOtpChallengeService.CooldownException khi gửi lại quá sớm
     * @throws AuthOtpChallengeService.RateLimitException khi đã đủ số lần gửi trong cửa sổ
     */
    @Override
    @Transactional
    public CreateResult resend(String challengeId, Instant requestedAt) {
        AuthOtpChallenge challenge = findForUpdate(challengeId);
        Instant now = requestedAt == null ? clock.instant() : requestedAt;
        if (challenge.getStatus() != OtpStatus.PENDING) {
            throw new AuthOtpChallengeService.InvalidStateException();
        }
        if (now.isBefore(challenge.getResendAvailableAt())) {
            throw new AuthOtpChallengeService.CooldownException();
        }
        if (!now.isBefore(challenge.getSendWindowStartedAt().plus(SEND_WINDOW))) {
            challenge.setSendWindowStartedAt(now);
            challenge.setSendCount(0);
        }
        if (challenge.getSendCount() >= MAX_SENDS) {
            throw new AuthOtpChallengeService.RateLimitException();
        }
        String otp = generator.generate();
        challenge.setOtpHash(hasher.hash(otp));
        challenge.setSendCount(challenge.getSendCount() + 1);
        challenge.setResendAvailableAt(now.plus(RESEND_COOLDOWN));
        challenge.setExpiresAt(now.plus(TTL));
        saveSafely(challenge);
        return new CreateResult(challenge, otp);
    }

    /**
     * Kiểm tra OTP: sai thì tăng số lần nhập sai và khóa thử thách khi đủ 5 lần; quá hạn thì đánh dấu hết hạn; đúng thì chuyển sang đã xác minh.
     *
     * @return kết quả xác minh, {@code accepted} true khi mã đúng
     */
    @Override
    @Transactional
    public VerifyResult verify(String challengeId, String otp, Instant requestedAt) {
        AuthOtpChallenge challenge = findForUpdate(challengeId);
        Instant now = requestedAt == null ? clock.instant() : requestedAt;
        if (challenge.getStatus() != OtpStatus.PENDING) {
            return new VerifyResult(false);
        }
        if (!now.isBefore(challenge.getExpiresAt())) {
            challenge.setStatus(OtpStatus.EXPIRED);
            saveSafely(challenge);
            return new VerifyResult(false);
        }
        if (otp == null || !hasher.matches(otp, challenge.getOtpHash())) {
            challenge.setFailedAttempts(challenge.getFailedAttempts() + 1);
            if (challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
                challenge.setStatus(OtpStatus.LOCKED);
            }
            saveSafely(challenge);
            return new VerifyResult(false);
        }
        challenge.setStatus(OtpStatus.VERIFIED);
        challenge.setVerifiedAt(now);
        saveSafely(challenge);
        return new VerifyResult(true);
    }

    /**
     * Đánh dấu thử thách đã xác minh là đã dùng.
     *
     * @throws AuthOtpChallengeService.InvalidStateException khi thử thách chưa được xác minh
     */
    @Override
    @Transactional
    public void consume(String challengeId, Instant requestedAt) {
        AuthOtpChallenge challenge = findForUpdate(challengeId);
        if (challenge.getStatus() != OtpStatus.VERIFIED) {
            throw new AuthOtpChallengeService.InvalidStateException();
        }
        challenge.setStatus(OtpStatus.CONSUMED);
        challenge.setConsumedAt(requestedAt == null ? clock.instant() : requestedAt);
        saveSafely(challenge);
    }

    /** Tìm thử thách theo id, ném {@code ChallengeNotFoundException} nếu không có. */
    private AuthOtpChallenge find(String challengeId) {
        return repository.findById(challengeId).orElseThrow(AuthOtpChallengeService.ChallengeNotFoundException::new);
    }

    /** Tìm thử thách và khóa bản ghi để ghi, ném {@code ChallengeNotFoundException} nếu không có. */
    private AuthOtpChallenge findForUpdate(String challengeId) {
        return repository.findByIdForUpdate(challengeId)
                .orElseThrow(AuthOtpChallengeService.ChallengeNotFoundException::new);
    }

    /** Lưu và ghi ngay; xung đột khóa lạc quan được đổi thành {@code ConflictException}. */
    private void saveSafely(AuthOtpChallenge challenge) {
        try {
            repository.saveAndFlush(challenge);
        } catch (OptimisticLockException | OptimisticLockingFailureException exception) {
            throw new AuthOtpChallengeService.ConflictException();
        }
    }
}
