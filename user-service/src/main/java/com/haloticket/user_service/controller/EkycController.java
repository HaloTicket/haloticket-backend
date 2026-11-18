package com.haloticket.user_service.controller;

import com.haloticket.user_service.dto.request.EkycStartSessionRequest;
import com.haloticket.user_service.dto.response.EkycOrganizerEligibilityResponse;
import com.haloticket.user_service.dto.response.EkycSessionResponse;
import com.haloticket.user_service.dto.response.EkycStatusResponse;
import com.haloticket.user_service.service.EkycService;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** API eKYC của người dùng hiện tại: xem trạng thái, mở phiên, nộp ảnh giấy tờ và kiểm tra điều kiện làm nhà tổ chức. */
@RestController
@RequestMapping("/api/users/me/ekyc")
@RequiredArgsConstructor
public class EkycController {
    private final EkycService ekycService;
    private final UserService userService;

    /**
     * Trả về trạng thái eKYC của người dùng đang đăng nhập.
     * Lỗi đồng bộ người dùng được bỏ qua để không chặn việc xem trạng thái.
     *
     * @param jwt token của người dùng hiện tại
     * @return trạng thái eKYC
     */
    @GetMapping
    public ResponseEntity<EkycStatusResponse> status(@AuthenticationPrincipal Jwt jwt) {
        if (jwt != null) {
            try {
                userService.syncCurrentUser(jwt);
            } catch (Exception ignored) {
                // Bỏ qua lỗi đồng bộ khi chỉ đọc, để không chặn việc xem trạng thái
            }
        }
        return ResponseEntity.ok(ekycService.getStatus(jwt != null ? jwt.getSubject() : null));
    }

    /**
     * Mở phiên eKYC mới sau khi người dùng đồng ý điều khoản.
     *
     * @param jwt token của người dùng hiện tại
     * @param idempotencyKey khóa chống gửi trùng, lấy từ header {@code Idempotency-Key}
     * @param request chứa việc đồng ý và phiên bản điều khoản
     * @return thông tin phiên vừa tạo
     */
    @PostMapping("/sessions")
    public ResponseEntity<EkycSessionResponse> startSession(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EkycStartSessionRequest request
    ) {
        if (jwt != null) {
            userService.syncCurrentUser(jwt);
        }
        return ResponseEntity.ok(ekycService.startSession(
                jwt != null ? jwt.getSubject() : null, request.consentAccepted(), request.consentVersion(), idempotencyKey
        ));
    }

    /**
     * Nhận ảnh mặt trước, mặt sau giấy tờ và ảnh khuôn mặt để xác minh cho một phiên eKYC.
     *
     * @param jwt token của người dùng hiện tại
     * @param sessionId mã phiên eKYC
     * @param frontImage ảnh mặt trước giấy tờ
     * @param backImage ảnh mặt sau giấy tờ
     * @param faceImage ảnh khuôn mặt
     * @return trạng thái eKYC sau khi xác minh
     */
    @PostMapping(
            value = "/sessions/{sessionId}/verify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<EkycStatusResponse> verify(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId,
            @RequestPart MultipartFile frontImage,
            @RequestPart MultipartFile backImage,
            @RequestPart MultipartFile faceImage
    ) {
        return ResponseEntity.ok(ekycService.verify(
                jwt.getSubject(), sessionId, frontImage, backImage, faceImage
        ));
    }

    /**
     * Cho biết người dùng hiện tại đã đủ điều kiện eKYC để làm nhà tổ chức hay chưa.
     *
     * @param jwt token của người dùng hiện tại
     * @return kết quả đủ điều kiện kèm mã lý do nếu chưa đạt
     */
    @GetMapping("/organizer-eligibility")
    public ResponseEntity<EkycOrganizerEligibilityResponse> organizerEligibility(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ekycService.getOrganizerEligibility(jwt.getSubject()));
    }
}
