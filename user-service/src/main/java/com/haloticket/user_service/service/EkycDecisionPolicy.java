package com.haloticket.user_service.service;

/** Chính sách quyết định kết quả eKYC từ phản hồi của VNPT: đạt hoặc bị từ chối kèm mã lý do. */
public class EkycDecisionPolicy {
    /** Tạo chính sách mặc định, không có cấu hình. */
    public EkycDecisionPolicy() {}

    /**
     * Đánh giá kết quả xác minh theo thứ tự: phản hồi hợp lệ, giấy tờ hợp lệ, đọc được số giấy tờ và họ tên,
     * kiểm tra người thật (liveness) và khớp khuôn mặt. Bước nào không đạt thì từ chối với mã lý do tương ứng.
     *
     * @param result kết quả trả về từ nhà cung cấp eKYC
     * @return quyết định đạt hoặc không đạt kèm mã lý do
     */
    public Decision evaluate(VnptEkycClient.VerificationResult result) {
        if (result == null || result.documentType() == null || result.providerReference() == null
                || result.providerReference().isBlank()) return Decision.reject("PROVIDER_RESULT_INVALID");
        if (!result.documentValid()) return Decision.reject("DOCUMENT_INVALID");
        if (result.documentNumber() == null || result.documentNumber().isBlank()
                || result.legalName() == null || result.legalName().isBlank()) {
            return Decision.reject("OCR_DATA_INCOMPLETE");
        }
        if (!result.faceLivenessPassed()) {
            return Decision.reject("FACE_LIVENESS_FAILED");
        }
        if (!result.faceMatched()) {
            return Decision.reject("FACE_MATCH_FAILED");
        }
        return Decision.approve();
    }

    /** Quyết định eKYC: {@code passed} cho biết đạt hay không, {@code reasonCode} là mã lý do khi bị từ chối. */
    public record Decision(boolean passed, String reasonCode) {
        /** Tạo quyết định đạt, không có mã lý do. */
        static Decision approve() { return new Decision(true, null); }
        /** Tạo quyết định từ chối với mã lý do. */
        static Decision reject(String reason) { return new Decision(false, reason); }
    }
}
