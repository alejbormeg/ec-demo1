package io.mateu.ecdemo1.iacp.infra.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;

/** JSON persistence boundary; never log the serialized value (it can contain credentials). */
public final class JsonDocuments {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private JsonDocuments() {}
    public static String write(Object value) {
        if (value == null) return null;
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot serialize configuration"); }
    }
    public static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot read stored configuration"); }
    }
    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("Output schema must be a JSON object"); }
    }
}
