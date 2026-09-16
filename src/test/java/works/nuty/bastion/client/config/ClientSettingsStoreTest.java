package works.nuty.bastion.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.state.DebuggerPreferences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSettingsStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsPreferencesAcrossFreshInstances() {
        Path file = temporaryDirectory.resolve("bastion.json");
        DebuggerPreferences first = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        first.setGizmoMode(ClientDebuggerState.GizmoMode.FOCUS);
        first.setInspectorVisible(true);
        first.setInspectorTab(DebuggerPreferences.InspectorTab.STACK);

        DebuggerPreferences reloaded = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });

        assertEquals(ClientDebuggerState.GizmoMode.FOCUS, reloaded.gizmoMode());
        assertEquals(Boolean.TRUE, reloaded.inspectorVisible());
        assertEquals(DebuggerPreferences.InspectorTab.STACK, reloaded.inspectorTab());
    }

    @Test
    void missingFileUsesAutomaticInspectorVisibilityAndDefaults() {
        DebuggerPreferences preferences = ClientSettingsStore.open(temporaryDirectory.resolve("bastion.json"),
            exception -> { throw new AssertionError(exception); });

        assertEquals(ClientDebuggerState.GizmoMode.GROUPED, preferences.gizmoMode());
        assertNull(preferences.inspectorVisible());
        assertEquals(DebuggerPreferences.InspectorTab.SOURCES, preferences.inspectorTab());
    }

    @Test
    void invalidFileReportsErrorAndIsNeverOverwritten() throws IOException {
        Path file = temporaryDirectory.resolve("bastion.json");
        String invalid = "{\"version\":1,\"gizmoMode\":\"NOT_A_MODE\"}";
        Files.writeString(file, invalid);
        List<Exception> errors = new ArrayList<>();

        DebuggerPreferences preferences = ClientSettingsStore.open(file, errors::add);
        preferences.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS);
        preferences.setInspectorVisible(false);

        assertFalse(errors.isEmpty());
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void eachActualChangeWritesTheCurrentSettings() throws IOException {
        Path file = temporaryDirectory.resolve("bastion.json");
        DebuggerPreferences preferences = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });

        preferences.setInspectorTab(DebuggerPreferences.InspectorTab.DETAILS);

        String saved = Files.readString(file);
        assertTrue(saved.contains("\"version\": 1"));
        assertTrue(saved.contains("\"gizmoMode\": \"GROUPED\""));
        assertTrue(saved.contains("\"inspectorVisible\": null"));
        assertTrue(saved.contains("\"inspectorTab\": \"DETAILS\""));
    }
}
