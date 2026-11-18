package com.haloticket.user_service.config;

import com.haloticket.user_service.enums.Gender;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Bộ chuyển đổi JPA lưu giới tính thành tên enum trong cột chuỗi, áp dụng cho mọi trường kiểu này. */
@Converter(autoApply = true)
public class GenderConverter implements AttributeConverter<Gender, String> {

    /** Chuyển enum thành tên để lưu; null thì lưu null. */
    @Override
    public String convertToDatabaseColumn(Gender attribute) {
        return attribute != null ? attribute.name() : null;
    }

    /** Đọc giới tính từ cột; rỗng thì null, giá trị lạ thì mặc định {@code OTHER}. */
    @Override
    public Gender convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return null;
        }
        try {
            return Gender.valueOf(dbData.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Gender.OTHER;
        }
    }
}
