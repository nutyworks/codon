package works.nuty.codon.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class WatchDefinitions {
    public static final int MAX_JSON_LENGTH = 8192;
    private WatchDefinitions() {}

    public static JsonArray encode(List<WatchSpec> specs) {
        List<WatchSpec> checked = validate(specs);
        JsonArray result = new JsonArray();
        for (WatchSpec spec : checked) {
            JsonObject object = new JsonObject();
            object.addProperty("kind", spec.kind().name());
            object.addProperty("target", spec.target());
            object.addProperty("path", spec.path());
            if (spec.executor() != null) object.addProperty("executor", spec.executor().toString());
            result.add(object);
        }
        return result;
    }

    public static List<WatchSpec> decode(JsonElement element) {
        if (element == null || !element.isJsonArray()) throw new IllegalArgumentException("watches must be an array");
        JsonArray array = element.getAsJsonArray();
        List<WatchSpec> result = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("watch must be an object");
            JsonObject object = item.getAsJsonObject();
            if (!object.has("kind") || !object.has("target") || !object.has("path")
                || !object.get("kind").isJsonPrimitive() || !object.get("target").isJsonPrimitive()
                || !object.get("path").isJsonPrimitive()
                || !object.getAsJsonPrimitive("kind").isString() || !object.getAsJsonPrimitive("target").isString()
                || !object.getAsJsonPrimitive("path").isString()) throw new IllegalArgumentException("malformed watch");
            try {
                WatchSpec.Kind kind = WatchSpec.Kind.valueOf(object.get("kind").getAsString());
                UUID executor = null;
                if (object.has("executor")) {
                    if (!object.get("executor").isJsonPrimitive() || !object.getAsJsonPrimitive("executor").isString())
                        throw new IllegalArgumentException("malformed executor");
                    String text = object.get("executor").getAsString();
                    if (text.length() != 36) throw new IllegalArgumentException("noncanonical executor");
                    executor = UUID.fromString(text);
                    if (!executor.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("noncanonical executor");
                }
                result.add(new WatchSpec(kind, object.get("target").getAsString(), object.get("path").getAsString(), executor));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid watch", e);
            }
        }
        return validate(result);
    }

    public static String toJson(List<WatchSpec> specs) {
        return encode(specs).toString();
    }

    public static List<WatchSpec> fromJson(String json) {
        if (json == null) throw new IllegalArgumentException("watch JSON is required");
        try {
            return decode(JsonParser.parseString(json));
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalArgumentException("invalid watch JSON", e);
        }
    }

    /** Serializes one bounded network page; persistence JSON is deliberately not length-bounded. */
    public static String toPageJson(List<WatchSpec> specs) {
        String json = toJson(specs);
        if (json.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("watch JSON page too long");
        return json;
    }

    /** Parses one bounded network page; persistence JSON is deliberately not length-bounded. */
    public static List<WatchSpec> fromPageJson(String json) {
        if (json == null || json.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("watch JSON page too long");
        return fromJson(json);
    }

    /** Splits a complete, unique definition list into independently valid transport pages. */
    public static List<List<WatchSpec>> pages(List<WatchSpec> specs) {
        List<WatchSpec> checked = validate(specs);
        if (checked.isEmpty()) return List.of(List.of());
        List<List<WatchSpec>> pages = new ArrayList<>();
        List<WatchSpec> page = new ArrayList<>();
        for (WatchSpec spec : checked) {
            List<WatchSpec> candidate = new ArrayList<>(page);
            candidate.add(spec);
            if (!page.isEmpty() && toJson(candidate).length() > MAX_JSON_LENGTH) {
                pages.add(List.copyOf(page));
                page = new ArrayList<>();
            }
            page.add(spec);
            if (toJson(page).length() > MAX_JSON_LENGTH) {
                throw new IllegalArgumentException("watch cannot fit in a JSON page");
            }
        }
        pages.add(List.copyOf(page));
        return List.copyOf(pages);
    }

    static List<WatchSpec> validate(List<WatchSpec> specs) {
        if (specs == null) throw new IllegalArgumentException("watches are required");
        Set<WatchSpec> unique = new HashSet<>();
        for (WatchSpec spec : specs) if (spec == null || !unique.add(spec)) throw new IllegalArgumentException("duplicate watch");
        return Collections.unmodifiableList(new ArrayList<>(specs));
    }
}
