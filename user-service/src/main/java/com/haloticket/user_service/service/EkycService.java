package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.response.EkycOrganizerEligibilityResponse;
import com.haloticket.user_service.dto.response.EkycSessionResponse;
import com.haloticket.user_service.dto.response.EkycStatusResponse;
import org.springframework.web.multipart.MultipartFile;

/** Nghiệp vụ xác minh danh tính (eKYC) cho người dùng muốn làm nhà tổ chức. */
public interface EkycService {
    /**
     * Mở phiên eKYC mới, hoặc trả lại phiên cũ nếu trùng khóa idempotency hoặc phiên gần nhất còn hạn.
     *
     * @param keycloakId id Keycloak của người dùng
     * @param consentAccepted người dùng đã đồng ý điều khoản chưa
     * @param consentVersion phiên bản điều khoản đã đồng ý
     * @param idempotencyKey khóa chống gửi trùng
     * @return thông tin phiên
     */
    EkycSessionResponse startSession(
            String keycloakId,
            boolean consentAccepted,
            String consentVersion,
            String idempotencyKey
    );

    /**
     * Lấy trạng thái eKYC hiện tại; trả về {@code NOT_STARTED} khi chưa có người dùng hoặc hồ sơ.
     *
     * @param keycloakId id Keycloak của người dùng (có thể rỗng)
     * @return trạng thái eKYC
     */
    EkycStatusResponse getStatus(String keycloakId);

    /**
     * Gửi ảnh giấy tờ và khuôn mặt tới nhà cung cấp để xác minh cho một phiên; nếu đạt thì lưu hồ sơ và cấp vai trò nhà tổ chức.
     *
     * @param keycloakId id Keycloak của người dùng
     * @param sessionId mã phiên eKYC
     * @param frontImage ảnh mặt trước giấy tờ
     * @param backImage ảnh mặt sau giấy tờ
     * @param faceImage ảnh khuôn mặt
     * @return trạng thái eKYC sau khi xác minh
     */
    EkycStatusResponse verify(
            String keycloakId,
            String sessionId,
            MultipartFile frontImage,
            MultipartFile backImage,
            MultipartFile faceImage
    );

    /**
     * Kiểm tra người dùng có hồ sơ eKYC còn hiệu lực để làm nhà tổ chức hay không.
     *
     * @param keycloakId id Keycloak của người dùng (có thể rỗng)
     * @return kết quả đủ điều kiện kèm mã lý do
     */
    EkycOrganizerEligibilityResponse getOrganizerEligibility(String keycloakId);

}
