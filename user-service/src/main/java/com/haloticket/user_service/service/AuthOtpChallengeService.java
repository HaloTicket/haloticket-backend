package com.haloticket.user_service.service;

import com.haloticket.user_service.entity.AuthOtpChallenge;

import java.time.Instant;

/** Quản lý vòng đời thử thách OTP: tạo, gửi lại, xác minh và đánh dấu đã dùng. */
public interface AuthOtpChallengeService {
    /** Tạo thử thách OTP mới (hoặc trả lại thử thách cũ nếu trùng khóa idempotency) và sinh mã OTP. */
    CreateResult create(OtpChallengeCommand command);
    /** Sinh lại OTP cho thử thách; báo lỗi nếu còn trong thời gian chờ hoặc vượt giới hạn gửi. */
    CreateResult resend(String challengeId, Instant now);
    /** Kiểm tra OTP người dùng nhập, tính số lần nhập sai và khóa khi vượt giới hạn. */
    VerifyResult verify(String challengeId, String otp, Instant now);
    /** Đánh dấu thử thách đã dùng để không thể xác minh lại. */
    void consume(String challengeId, Instant now);

    /** Kết quả tạo hoặc gửi lại: thử thách đã lưu và mã OTP dạng chữ rõ để gửi đi (không lưu chữ rõ). */
    record CreateResult(AuthOtpChallenge challenge, String plaintextOtp) {}
    /**
     * Kết quả xác minh OTP: {@code accepted} là true khi mã đúng. Khi đúng, {@code subject} (email hoặc định danh mà mã đã gửi tới)
     * và {@code purpose} cho phép người gọi kiểm tra mã này đúng cho yêu cầu đang xử lý; khi sai cả hai là {@code null}.
     */
    record VerifyResult(boolean accepted, String subject, com.haloticket.user_service.enums.OtpPurpose purpose) {
        /** Kết quả không kèm thông tin thử thách (mã sai hoặc không dùng tới). */
        public VerifyResult(boolean accepted) {
            this(accepted, null, null);
        }
    }

    /** Báo yêu cầu gửi lại quá sớm, còn trong thời gian chờ. */
    class CooldownException extends RuntimeException {}
    /** Báo vượt giới hạn số lần gửi OTP. */
    class RateLimitException extends RuntimeException {}
    /** Báo không tìm thấy thử thách OTP. */
    class ChallengeNotFoundException extends RuntimeException {}
    /** Báo thử thách không còn ở trạng thái dùng được (hết hạn, đã dùng hoặc bị khóa). */
    class InvalidStateException extends RuntimeException {}
    /** Báo yêu cầu xung đột với một yêu cầu khác cùng lúc. */
    class ConflictException extends RuntimeException {}
    /** Báo khóa idempotency đã được dùng cho một yêu cầu khác nội dung. */
    class IdempotencyKeyReuseException extends RuntimeException {}
}
