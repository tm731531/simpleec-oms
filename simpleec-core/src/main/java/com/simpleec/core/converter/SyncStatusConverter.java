package com.simpleec.core.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleec.core.dto.SyncStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SyncStatusConverter implements AttributeConverter<SyncStatus, String> {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(SyncStatus attribute) {
        if (attribute == null) return null;
        try { return objectMapper.writeValueAsString(attribute); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize SyncStatus", e); }
    }

    @Override
    public SyncStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try { return objectMapper.readValue(dbData, SyncStatus.class); }
        catch (Exception e) { throw new RuntimeException("Failed to deserialize SyncStatus", e); }
    }
}
