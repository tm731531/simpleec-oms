package com.simpleec.core.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA AttributeConverter: JsonNode ↔ String (JSONB stored as text)
 *
 * Replaces @JdbcTypeCode(Types.OTHER) which fails to map PGobject → JsonNode.
 */
@Slf4j
@Converter
public class JsonNodeConverter implements AttributeConverter<JsonNode, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        if (attribute == null) return null;
        return attribute.toString();
    }

    @Override
    public JsonNode convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(dbData);
        } catch (Exception e) {
            log.warn("Failed to parse JSONB value as JsonNode: {}", dbData, e);
            return MAPPER.createObjectNode();
        }
    }
}
