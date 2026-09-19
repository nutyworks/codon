package works.nuty.codon.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchSpec;

/** Strict JSON boundary for the client-authored, read-only editor query. */
public final class WatchEditorQueryCodec {
    public static final int MAX_JSON_LENGTH = 2048;
    private WatchEditorQueryCodec() {}

    public static String toJson(WatchEditorQuery query) {
        JsonObject object = new JsonObject();
        object.addProperty("mode", query.mode().name());
        object.addProperty("kind", query.kind().name());
        object.addProperty("target", query.target());
        object.addProperty("path", query.path());
        if (query.executor() != null) object.addProperty("executor", query.executor().toString());
        object.addProperty("search", query.search());
        object.addProperty("offset", query.offset());
        String json = object.toString();
        if (json.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("editor query too long");
        return json;
    }

    public static WatchEditorQuery fromJson(String json) {
        if (json == null || json.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("editor query too long");
        try {
            if (!JsonParser.parseString(json).isJsonObject()) throw new IllegalArgumentException("editor query must be object");
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            WatchEditorQuery.Mode mode = WatchEditorQuery.Mode.valueOf(string(object, "mode"));
            WatchSpec.Kind kind = WatchSpec.Kind.valueOf(string(object, "kind"));
            UUID executor = executor(object);
            if (!object.has("offset") || !object.get("offset").isJsonPrimitive()
                || !object.getAsJsonPrimitive("offset").isNumber()) throw new IllegalArgumentException("editor offset missing");
            return new WatchEditorQuery(mode, kind, string(object, "target"), string(object, "path"), executor,
                string(object, "search"), object.get("offset").getAsInt());
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalArgumentException("invalid editor query", e);
        }
    }

    private static String string(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive() || !object.getAsJsonPrimitive(name).isString())
            throw new IllegalArgumentException("editor " + name + " missing");
        return object.get(name).getAsString();
    }

    private static @Nullable UUID executor(JsonObject object) {
        if (!object.has("executor")) return null;
        String text = string(object, "executor");
        if (text.length() != 36) throw new IllegalArgumentException("invalid editor executor");
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("invalid editor executor");
        return id;
    }
}
