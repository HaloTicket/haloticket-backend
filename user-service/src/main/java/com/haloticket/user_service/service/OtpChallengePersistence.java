package com.haloticket.user_service.service;

import com.haloticket.user_service.entity.AuthOtpChallenge;
import com.haloticket.user_service.repository.AuthOtpChallengeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Lưu thử thách OTP trong giao dịch riêng ({@code REQUIRES_NEW}) để kết quả không bị hoàn tác theo giao dịch của bên gọi. */
@Component
public class OtpChallengePersistence {
    private final AuthOtpChallengeRepository repository;

    /** Tạo đối tượng với kho lưu thử thách OTP. */
    public OtpChallengePersistence(AuthOtpChallengeRepository repository) {
        this.repository = repository;
    }

    /** Thêm thử thách mới và ghi ngay xuống cơ sở dữ liệu trong giao dịch riêng, để lỗi trùng khóa được phát hiện tại chỗ. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthOtpChallenge insert(AuthOtpChallenge challenge) {
        return repository.saveAndFlush(challenge);
    }

    /** Lưu thử thách đã được đánh dấu hết hạn trong giao dịch riêng, để trạng thái vẫn được ghi dù bên gọi ném ngoại lệ. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(AuthOtpChallenge challenge) {
        repository.saveAndFlush(challenge);
    }
}
