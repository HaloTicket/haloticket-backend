package com.haloticket.user_service.config;

import com.haloticket.user_service.enums.EkycDocumentType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EkycDocumentTypeConverter implements AttributeConverter<EkycDocumentType, String> {

    @Override
    public String convertToDatabaseColumn(EkycDocumentType attribute) {
        return attribute != null ? attribute.name() : null;
    }

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
