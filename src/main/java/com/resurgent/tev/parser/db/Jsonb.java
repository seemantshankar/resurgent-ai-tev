package com.resurgent.tev.parser.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Centralized Jackson converters (ADR 0002): JSONB and INT[] values are stored
 * as TEXT and validated on every read/write.
 */
public final class Jsonb {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Jsonb() {}

    public static String toJson(Object value) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(value);
    }

    public static <T> T fromJson(String json, Class<T> clazz) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json, clazz);
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json, typeRef);
    }

    /** Serializes an int array to a JSON array TEXT (ADR 0002: INT[] → JSON-array TEXT). */
    public static String toJsonArray(int[] values) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(values);
    }

    /** Parses a JSON-array TEXT back to an int array. */
    public static int[] fromJsonArray(String json) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json, int[].class);
    }
}
