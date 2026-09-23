package works.nuty.codon.client.ui;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.ClientFunctionSourceState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionSourceScreenLayoutTest {
    @Test
    void narrowMinecraftGuiSizesUseOnePaneFunctionDrawer() {
        ClientFunctionSourceState.ScreenLayout tiny = ClientFunctionSourceState.ScreenLayout.forScreen(320, 180);
        ClientFunctionSourceState.ScreenLayout compact = ClientFunctionSourceState.ScreenLayout.forScreen(480, 270);

        assertTrue(tiny.drawerMode());
        assertTrue(compact.drawerMode());
        assertEquals(tiny.panelWidth(), tiny.treeWidth());
        assertEquals(compact.panelWidth(), compact.treeWidth());
        assertEquals(168, tiny.panelHeight());
        assertEquals(258, compact.panelHeight());
    }

    @Test
    void wideGuiKeepsTheFunctionTreeBesideTheSourcePane() {
        ClientFunctionSourceState.ScreenLayout wide = ClientFunctionSourceState.ScreenLayout.forScreen(1024, 576);

        assertFalse(wide.drawerMode());
        assertTrue(wide.treeWidth() >= 150);
        assertTrue(wide.treeWidth() < wide.panelWidth());
    }
}
