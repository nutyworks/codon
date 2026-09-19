package works.nuty.codon.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.client.state.ClientNbtState;

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
        Path file = temporaryDirectory.resolve("codon.json");
        DebuggerPreferences first = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        first.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS);
        first.setInspectorVisible(true);
        first.setInspectorTab(DebuggerPreferences.InspectorTab.STACK);
        first.setNbtExpanded(false);
        first.setKeepFreecam(true);

        DebuggerPreferences reloaded = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });

        assertEquals(ClientDebuggerState.GizmoMode.LABELS, reloaded.gizmoMode());
        assertEquals(Boolean.TRUE, reloaded.inspectorVisible());
        assertEquals(DebuggerPreferences.InspectorTab.STACK, reloaded.inspectorTab());
        assertFalse(reloaded.nbtExpanded());
        assertTrue(reloaded.keepFreecam());
    }

    @Test
    void missingFileUsesAutomaticInspectorVisibilityAndDefaults() {
        DebuggerPreferences preferences = ClientSettingsStore.open(temporaryDirectory.resolve("codon.json"),
            exception -> { throw new AssertionError(exception); });

        assertEquals(ClientDebuggerState.GizmoMode.GROUPED, preferences.gizmoMode());
        assertNull(preferences.inspectorVisible());
        assertEquals(DebuggerPreferences.InspectorTab.SOURCES, preferences.inspectorTab());
        assertTrue(preferences.nbtExpanded());
        assertFalse(preferences.keepFreecam());
    }

    @Test
    void nbtCollapseSurvivesStateResetAndFreshSettingsLoad() {
        Path file = temporaryDirectory.resolve("nbt.json");
        DebuggerPreferences preferences = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        ClientNbtState nbt = new ClientNbtState(() -> 0);
        nbt.setEnabled(preferences.nbtExpanded());
        nbt.setEnabledListener(preferences::setNbtExpanded);
        nbt.setEnabled(false);
        nbt.reset();
        assertFalse(nbt.enabled());
        assertFalse(ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); }).nbtExpanded());
        nbt.setEnabled(true);
        assertTrue(ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); }).nbtExpanded());
    }

    @Test
    void olderSettingsDefaultToExpandedAndInvalidExpansionIsPreserved() throws IOException {
        Path file = temporaryDirectory.resolve("nbt.json");
        Files.writeString(file, "{\"version\":1,\"inspectorTab\":\"STACK\"}");
        DebuggerPreferences old = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        assertTrue(old.nbtExpanded());
        assertEquals(DebuggerPreferences.InspectorTab.STACK, old.inspectorTab());
        String invalid = "{\"version\":1,\"nbtExpanded\":\"false\"}";
        Files.writeString(file, invalid);
        List<Exception> errors = new ArrayList<>();
        ClientSettingsStore.open(file, errors::add).setNbtExpanded(false);
        assertEquals(1, errors.size());
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void invalidFileReportsErrorAndIsNeverOverwritten() throws IOException {
        Path file = temporaryDirectory.resolve("codon.json");
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
        Path file = temporaryDirectory.resolve("codon.json");
        DebuggerPreferences preferences = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });

        preferences.setInspectorTab(DebuggerPreferences.InspectorTab.DETAILS);

        String saved = Files.readString(file);
        assertTrue(saved.contains("\"version\": 1"));
        assertTrue(saved.contains("\"gizmoMode\": \"GROUPED\""));
        assertTrue(saved.contains("\"inspectorVisible\": null"));
        assertTrue(saved.contains("\"inspectorTab\": \"DETAILS\""));
    }
}
