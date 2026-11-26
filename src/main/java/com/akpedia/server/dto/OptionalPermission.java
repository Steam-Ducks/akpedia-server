package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One optional permission of a version of the terms of use. */
public record OptionalPermission(String id, String label, String description) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<OptionalPermission>> LIST = new TypeReference<>() { };

    /** The permissions stored in a {@code terms_versions.optional_permissions} column. */
    public static List<OptionalPermission> listOf(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : JSON.readValue(json, LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid optional permissions JSON", e);
        }
    }

    public static String toJson(List<OptionalPermission> permissions) {
        try {
            return JSON.writeValueAsString(permissions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
