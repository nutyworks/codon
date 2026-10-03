package works.nuty.codon.client.ui;

import org.junit.jupiter.api.Test;
import works.nuty.codon.client.state.ClientFunctionSourceState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionSourceScreenLayoutTest {
    @Test void compactSourceDoesNotReserveARowForRemovedConditionButtons() {
        var wide = ClientFunctionSourceState.ScreenLayout.forScreen(1024, 576);
        assertEquals(ClientFunctionSourceState.ScreenLayout.sourceInset(false),
            ClientFunctionSourceState.ScreenLayout.sourceInset(true));
        assertEquals(ClientFunctionSourceState.ScreenLayout.sourceRows(wide.panelHeight(), false),
            ClientFunctionSourceState.ScreenLayout.sourceRows(wide.panelHeight(), true));
    }
    @Test void sourceRowsStayAboveTheScrollbarAtSmallLogicalSizes() {
        for (int height : new int[]{180, 240, 270}) {
            var layout = ClientFunctionSourceState.ScreenLayout.forScreen(320, height);
            int sourceBottom = ClientFunctionSourceState.ScreenLayout.sourceInset(true)
                + ClientFunctionSourceState.ScreenLayout.sourceRows(layout.panelHeight(), true) * 18;
            int scrollbarHitTop = ClientFunctionSourceState.ScreenLayout.scrollbarInset(layout.panelHeight()) - 2;
            assertTrue(sourceBottom <= scrollbarHitTop, "source rows do not overlap scrollbar input at height " + height);
        }
    }
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
