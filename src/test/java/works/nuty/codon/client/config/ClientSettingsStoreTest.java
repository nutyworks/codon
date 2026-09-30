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
    void uiScaleDefaultsRoundTripAndResetWithoutChangingOtherPreferences() throws IOException {
        Path file = temporaryDirectory.resolve("scale.json");
        Files.writeString(file, "{\"version\":1}");
        var preferences = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        assertEquals(DebuggerPreferences.UiScaleMode.FOLLOW_GAME, preferences.uiScaleMode());
        assertEquals(8, preferences.customUiScale());
        assertFalse(preferences.customUiScaleInitialized());
        preferences.setBackgroundOpacity(37);
        preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
        for (int scale : new int[]{4, 9, 16}) {
            preferences.setCustomUiScale(scale);
            var reloaded = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
            assertEquals(DebuggerPreferences.UiScaleMode.CUSTOM, reloaded.uiScaleMode());
            assertEquals(scale, reloaded.customUiScale());
        }
        preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.FOLLOW_GAME);
        assertEquals(16, ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); }).customUiScale());
        preferences.resetUiScale();
        var reset = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        assertEquals(DebuggerPreferences.UiScaleMode.FOLLOW_GAME, reset.uiScaleMode());
        assertEquals(8, reset.customUiScale());
        assertFalse(reset.customUiScaleInitialized());
        assertEquals(37, reset.backgroundOpacity());
    }

    @Test
    void firstUseAndUserRequestSurviveFreshSettingsLoadsAndReset() throws IOException {
        Path file = temporaryDirectory.resolve("first-scale.json");
        var preferences = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        preferences.setBackgroundOpacity(37);
        assertFalse(Files.readString(file).contains("customUiScale"));
        preferences = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        preferences.selectCustomUiScale(6);
        assertEquals(24, preferences.customUiScale());
        preferences.setCustomUiScale(17);
        preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.FOLLOW_GAME);
        var reloaded = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        assertTrue(reloaded.customUiScaleInitialized());
        reloaded.selectCustomUiScale(3);
        assertEquals(17, reloaded.customUiScale());
        reloaded.resetUiScale();
        assertFalse(Files.readString(file).contains("customUiScale"));
        var reset = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        assertFalse(reset.customUiScaleInitialized());
        reset.selectCustomUiScale(3);
        assertEquals(12, reset.customUiScale());
        assertEquals(37, reset.backgroundOpacity());
    }

    @Test
    void legacyRequestsRemainInitializedInEitherMode() throws IOException {
        Path file = temporaryDirectory.resolve("legacy-scale.json");
        for (var mode : DebuggerPreferences.UiScaleMode.values()) {
            for (int saved : new int[]{4, 8, 16}) {
                Files.writeString(file, "{\"version\":1,\"uiScaleMode\":\"" + mode + "\",\"customUiScale\":" + saved + "}");
                var preferences = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
                assertTrue(preferences.customUiScaleInitialized());
                preferences.selectCustomUiScale(3);
                assertEquals(saved, preferences.customUiScale());
                preferences.resetUiScale();
                var reset = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
                reset.selectCustomUiScale(3);
                assertEquals(12, reset.customUiScale());
            }
        }
    }

    @Test
    void invalidScaleSettingsArePreservedWithoutWrites() throws IOException {
        Path file = temporaryDirectory.resolve("invalid-scale.json");
        for (String property : List.of("\"customUiScale\":3", "\"customUiScale\":2147483645", "\"customUiScale\":8.5",
            "\"customUiScale\":\"8\"", "\"customUiScale\":null", "\"uiScaleMode\":\"OTHER\"", "\"uiScaleMode\":true")) {
            String original = "{\"version\":1," + property + "}";
            Files.writeString(file, original);
            List<Exception> errors = new ArrayList<>();
            var preferences = ClientSettingsStore.open(file, errors::add);
            assertEquals(1, errors.size());
            preferences.setUiScaleMode(DebuggerPreferences.UiScaleMode.CUSTOM);
            assertEquals(original, Files.readString(file));
        }
    }

    @Test
    void opacityEndpointsRoundTripAndOldSettingsKeepOriginalAppearance() throws IOException {
        Path file = temporaryDirectory.resolve("opacity.json");
        Files.writeString(file, "{\"version\":1}");
        DebuggerPreferences preferences = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        assertEquals(100, preferences.backgroundOpacity());
        for (int opacity : new int[]{0, 100}) {
            preferences.setBackgroundOpacity(opacity);
            assertEquals(opacity, ClientSettingsStore.open(file,
                exception -> { throw new AssertionError(exception); }).backgroundOpacity());
        }
    }

    @Test
    void invalidOpacityDoesNotOverwriteSettings() throws IOException {
        Path file = temporaryDirectory.resolve("invalid-opacity.json");
        for (String value : List.of("-1", "101", "0.5", "null", "true", "\"50\"")) {
            String original = "{\"version\":1,\"backgroundOpacity\":" + value + "}";
            Files.writeString(file, original);
            List<Exception> errors = new ArrayList<>();
            DebuggerPreferences preferences = ClientSettingsStore.open(file, errors::add);
            assertEquals(1, errors.size());
            preferences.setBackgroundOpacity(20);
            assertEquals(original, Files.readString(file));
        }
    }

    @Test
    void roundTripsPreferencesAcrossFreshInstances() {
        Path file = temporaryDirectory.resolve("codon.json");
        DebuggerPreferences first = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });
        first.setGizmoMode(ClientDebuggerState.GizmoMode.LABELS);
        first.setInspectorVisible(true);
        first.setInspectorTab(DebuggerPreferences.InspectorTab.STACK);
        first.setNbtExpanded(false);
        first.setKeepFreecam(true);
        first.setWatchesVisible(false);
        first.setCommandVisible(false);
        first.setBackgroundOpacity(37);

        DebuggerPreferences reloaded = ClientSettingsStore.open(file, exception -> { throw new AssertionError(exception); });

        assertEquals(ClientDebuggerState.GizmoMode.LABELS, reloaded.gizmoMode());
        assertEquals(Boolean.TRUE, reloaded.inspectorVisible());
        assertEquals(DebuggerPreferences.InspectorTab.STACK, reloaded.inspectorTab());
        assertFalse(reloaded.nbtExpanded());
        assertTrue(reloaded.keepFreecam());
        assertFalse(reloaded.watchesVisible());
        assertFalse(reloaded.commandVisible());
        assertEquals(37, reloaded.backgroundOpacity());
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
        assertTrue(preferences.watchesVisible());
        assertTrue(preferences.commandVisible());
        assertEquals(100, preferences.backgroundOpacity());
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
