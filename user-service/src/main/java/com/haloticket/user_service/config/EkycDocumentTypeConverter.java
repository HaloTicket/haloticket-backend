package com.haloticket.user_service.config;

import com.haloticket.user_service.enums.EkycDocumentType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Bộ chuyển đổi JPA lưu loại giấy tờ eKYC thành tên enum trong cột chuỗi, áp dụng cho mọi trường kiểu này. */
@Converter(autoApply = true)
public class EkycDocumentTypeConverter implements AttributeConverter<EkycDocumentType, String> {

    /** Chuyển enum thành tên để lưu; null thì lưu null. */
    @Override
    public String convertToDatabaseColumn(EkycDocumentType attribute) {
        return attribute != null ? attribute.name() : null;
    }

    /** Đọc loại giấy tờ từ cột; rỗng thì null, giá trị lạ thì mặc định {@code ID_CARD}. */
    @Override
    public EkycDocumentType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return null;
        }
        try {
            return EkycDocumentType.valueOf(dbData.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return EkycDocumentType.ID_CARD;
        }
    }
}
