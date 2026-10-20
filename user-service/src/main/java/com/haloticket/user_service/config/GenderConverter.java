package com.haloticket.user_service.config;

import com.haloticket.user_service.enums.Gender;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class GenderConverter implements AttributeConverter<Gender, String> {

    @Override
    public String convertToDatabaseColumn(Gender attribute) {
        return attribute != null ? attribute.name() : null;
    }

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
