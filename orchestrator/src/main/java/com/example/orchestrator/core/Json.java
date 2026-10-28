package com.example.orchestrator.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared JSON mapper and content hashing. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    /** SHA-256 of the content with object keys sorted, so equal content always hashes the same. */
    public static String hash(Object value) {
        return sha256(canonical(toTree(value)));
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canonical(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            keys.sort(null);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) {
                if (object.get(key).isNull()) continue;
                if (out.length() > 1) out.append(',');
                out.append(quote(key)).append(':').append(canonical(object.get(key)));
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(canonical(item)));
            return "[" + String.join(",", items) + "]";
        }
        return node.toString();
    }

    private static String quote(String text) {
        return MAPPER.getNodeFactory().textNode(text).toString();
    }
}
