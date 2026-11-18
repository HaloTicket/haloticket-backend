package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.EkycDocumentType;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/** Máy khách gọi dịch vụ eKYC của VNPT để đọc giấy tờ và đối chiếu khuôn mặt. */
public interface VnptEkycClient {
    /** Bảo đảm máy khách đã được cấu hình và sẵn sàng; nếu chưa thì ném ngoại lệ. */
    void requireReady();
    /** Gửi ảnh mặt trước, mặt sau giấy tờ và ảnh khuôn mặt tới VNPT, trả về kết quả đọc giấy tờ và đối chiếu. */
    VerificationResult verify(MultipartFile frontImage, MultipartFile backImage, MultipartFile faceImage);

    /** Kết quả xác minh từ VNPT: mã tham chiếu, loại và số giấy tờ, họ tên, các cờ hợp lệ, liveness, khớp mặt và điểm khớp. */
    record VerificationResult(
            String providerReference,
            EkycDocumentType documentType,
            String documentNumber,
            String legalName,
            boolean documentValid,
            boolean documentLivenessPassed,
            boolean faceLivenessPassed,
            boolean faceMatched,
            double matchScore,
            LocalDate documentExpiresOn,
            String birthDay,
            String address
    ) {
        /** Dựng kết quả không có ngày sinh và địa chỉ (hai trường đó là {@code null}). */
        public VerificationResult(
                String providerReference,
                EkycDocumentType documentType,
                String documentNumber,
                String legalName,
                boolean documentValid,
                boolean documentLivenessPassed,
                boolean faceLivenessPassed,
                boolean faceMatched,
                double matchScore,
                LocalDate documentExpiresOn
        ) {
            this(providerReference, documentType, documentNumber, legalName, documentValid,
                    documentLivenessPassed, faceLivenessPassed, faceMatched, matchScore, documentExpiresOn, null, null);
        }
    }
}
