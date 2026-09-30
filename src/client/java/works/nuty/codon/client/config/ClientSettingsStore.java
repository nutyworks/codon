package works.nuty.codon.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.persistence.JsonFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Reads and writes the client-wide debugger preferences. */
public final class ClientSettingsStore {
    private static final int VERSION = 1;

    private ClientSettingsStore() {
    }

    public static DebuggerPreferences open(java.nio.file.Path file, Consumer<Exception> onError) {
        Objects.requireNonNull(file);
        Objects.requireNonNull(onError);
        DebuggerPreferences preferences = new DebuggerPreferences();
        JsonFile jsonFile = new JsonFile(file);
        try {
            Optional<JsonObject> saved = jsonFile.read();
            if (saved.isEmpty()) return attachWriter(preferences, jsonFile, onError);
            DebuggerPreferences loaded = new DebuggerPreferences();
            load(saved.get(), loaded);
            return attachWriter(loaded, jsonFile, onError);
        } catch (Exception exception) {
            onError.accept(exception);
            // Do not overwrite a file which could be repaired manually or by a later version.
            return preferences;
        }
    }

    private static DebuggerPreferences attachWriter(DebuggerPreferences preferences, JsonFile file,
                                                     Consumer<Exception> onError) {
        preferences.setChangeListener(() -> {
            try {
                file.write(encode(preferences));
            } catch (Exception exception) {
                onError.accept(exception);
            }
        });
        return preferences;
    }

    private static void load(JsonObject json, DebuggerPreferences preferences) throws IOException {
        if (!json.has("version") || !isVersion(json.get("version"))) {
            throw new IOException("Unsupported Codon client settings version");
        }
        if (json.has("uiScaleMode")) {
            preferences.setUiScaleMode(enumValue(json.get("uiScaleMode"), DebuggerPreferences.UiScaleMode.class, "uiScaleMode"));
        }
        if (json.has("customUiScale")) {
            JsonElement value = json.get("customUiScale");
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
                int scale = new BigDecimal(value.getAsString()).intValueExact();
                if (scale < DebuggerPreferences.MIN_UI_SCALE || scale > DebuggerPreferences.MAX_UI_SCALE) throw new NumberFormatException();
                preferences.setCustomUiScale(scale);
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IOException("Invalid customUiScale in Codon client settings", exception);
            }
        }
        if (json.has("backgroundOpacity")) {
            JsonElement value = json.get("backgroundOpacity");
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    throw new NumberFormatException();
                }
                int opacity = new BigDecimal(value.getAsString()).intValueExact();
                if (opacity < 0 || opacity > 100) throw new NumberFormatException();
                preferences.setBackgroundOpacity(opacity);
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IOException("Invalid backgroundOpacity in Codon client settings", exception);
            }
        }
        if (json.has("gizmoMode")) {
            preferences.setGizmoMode(enumValue(json.get("gizmoMode"), ClientDebuggerState.GizmoMode.class, "gizmoMode"));
        }
        if (json.has("inspectorVisible")) {
            JsonElement visible = json.get("inspectorVisible");
            if (visible.isJsonNull()) {
                preferences.setInspectorVisible(null);
            } else if (visible.isJsonPrimitive() && visible.getAsJsonPrimitive().isBoolean()) {
                preferences.setInspectorVisible(visible.getAsBoolean());
            } else {
                throw new IOException("Invalid inspectorVisible in Codon client settings");
            }
        }
        if (json.has("inspectorTab")) {
            preferences.setInspectorTab(enumValue(json.get("inspectorTab"), DebuggerPreferences.InspectorTab.class, "inspectorTab"));
        }
        if (json.has("watchesVisible")) {
            JsonElement visible = json.get("watchesVisible");
            if (!visible.isJsonPrimitive() || !visible.getAsJsonPrimitive().isBoolean()) {
                throw new IOException("Invalid watchesVisible in Codon client settings");
            }
            preferences.setWatchesVisible(visible.getAsBoolean());
        }
        if (json.has("commandVisible")) {
            JsonElement visible = json.get("commandVisible");
            if (!visible.isJsonPrimitive() || !visible.getAsJsonPrimitive().isBoolean()) {
                throw new IOException("Invalid commandVisible in Codon client settings");
            }
            preferences.setCommandVisible(visible.getAsBoolean());
        }
        if (json.has("keepFreecam")) {
            JsonElement keep = json.get("keepFreecam");
            if (!keep.isJsonPrimitive() || !keep.getAsJsonPrimitive().isBoolean()) {
                throw new IOException("Invalid keepFreecam in Codon client settings");
            }
            preferences.setKeepFreecam(keep.getAsBoolean());
        }
        if (json.has("nbtExpanded")) {
            JsonElement expanded = json.get("nbtExpanded");
            if (!expanded.isJsonPrimitive() || !expanded.getAsJsonPrimitive().isBoolean()) {
                throw new IOException("Invalid nbtExpanded in Codon client settings");
            }
            preferences.setNbtExpanded(expanded.getAsBoolean());
        }
    }

    private static boolean isVersion(JsonElement value) {
        if (!value.isJsonPrimitive()) return false;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) return false;
        try {
            return new BigDecimal(primitive.getAsString()).compareTo(BigDecimal.valueOf(VERSION)) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static <T extends Enum<T>> T enumValue(JsonElement value, Class<T> type, String name) throws IOException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Invalid " + name + " in Codon client settings");
        }
        try {
            return Enum.valueOf(type, value.getAsString());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid " + name + " in Codon client settings", exception);
        }
    }

    private static JsonObject encode(DebuggerPreferences preferences) {
        JsonObject json = new JsonObject();
        json.addProperty("version", VERSION);
        json.addProperty("uiScaleMode", preferences.uiScaleMode().name());
        // Absence means first use. Legacy files containing a request keep that value.
        if (preferences.customUiScaleInitialized()) json.addProperty("customUiScale", preferences.customUiScale());
        json.addProperty("backgroundOpacity", preferences.backgroundOpacity());
        json.addProperty("gizmoMode", preferences.gizmoMode().name());
        if (preferences.inspectorVisible() == null) {
            json.add("inspectorVisible", JsonNull.INSTANCE);
        } else {
            json.addProperty("inspectorVisible", preferences.inspectorVisible());
        }
        json.addProperty("inspectorTab", preferences.inspectorTab().name());
        json.addProperty("nbtExpanded", preferences.nbtExpanded());
        json.addProperty("keepFreecam", preferences.keepFreecam());
        json.addProperty("watchesVisible", preferences.watchesVisible());
        json.addProperty("commandVisible", preferences.commandVisible());
        return json;
    }
}
