package com.notification.platform.worker.domain;

import java.util.Map;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores the template payload as a JSON string.
 *
 * <p>Deliberately a plain converter over a {@code text} column rather than a Hibernate JSON
 * type: the payload is never queried by key, so a native JSON column would buy nothing and
 * cost a dialect-specific mapping.
 */
@Converter
public class PayloadConverter implements AttributeConverter<Map<String, String>, String> {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {
  };

  @Override
  public String convertToDatabaseColumn(Map<String, String> attribute) {
    return MAPPER.writeValueAsString(attribute == null ? Map.of() : attribute);
  }

  @Override
  public Map<String, String> convertToEntityAttribute(String dbData) {
    return dbData == null || dbData.isBlank() ? Map.of() : MAPPER.readValue(dbData, TYPE);
  }
}
