package com.simpleec.core.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;

/**
 * JPA AttributeConverter: JsonNode ↔ PGobject (PostgreSQL JSONB)
 *
 * Uses PGobject to properly handle PostgreSQL JSONB type.
 */
@Slf4j
@Converter
public class JsonNodeConverter implements AttributeConverter<JsonNode, Object> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object convertToDatabaseColumn(JsonNode attribute) {
        if (attribute == null) return null;
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            // 使用 Jackson 序列化，確保正確的 JSON 字串
            pgObject.setValue(MAPPER.writeValueAsString(attribute));
            return pgObject;
        } catch (Exception e) {
            log.error("Failed to convert JsonNode to PGobject", e);
            return null;
        }
    }

    @Override
    public JsonNode convertToEntityAttribute(Object dbData) {
        String jsonStr = null;
        if (dbData instanceof PGobject) {
            jsonStr = ((PGobject) dbData).getValue();
        } else if (dbData instanceof String) {
            jsonStr = (String) dbData;
        }

        if (jsonStr == null || jsonStr.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(jsonStr);
        } catch (Exception e) {
            log.warn("Failed to parse JSONB value as JsonNode: {}", jsonStr, e);
            return MAPPER.createObjectNode();
        }
    }
}
