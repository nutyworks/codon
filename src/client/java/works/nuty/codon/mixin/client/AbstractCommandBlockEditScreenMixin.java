package works.nuty.codon.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.BreakpointConditionScreen;
import works.nuty.codon.client.ui.BreakpointUi;
import works.nuty.codon.client.ui.DebuggerTheme;
import works.nuty.codon.client.ui.layout.CommandFlowLayout;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;

/** Adds direct block and recorded-stage breakpoint controls to the vanilla editor. */
@Mixin(AbstractCommandBlockEditScreen.class)
public abstract class AbstractCommandBlockEditScreenMixin extends Screen {
    @Shadow protected EditBox commandEdit;
    @Shadow protected Button doneButton;
    @Shadow protected abstract BaseCommandBlock getCommandBlock();

    @Unique private @Nullable Button codon$blockButton;
    @Unique private @Nullable Button codon$stageButton;
    @Unique private @Nullable Button codon$conditionButton;
    @Unique private @Nullable String codon$requestedCommand;
    @Unique private @Nullable BreakpointTarget codon$selected;
    @Unique private boolean codon$keyboardStages;
    @Unique private int codon$scrollRow;
    @Unique private int codon$totalRows;
    @Unique private final List<StageHit> codon$stageHits = new ArrayList<>();

    protected AbstractCommandBlockEditScreenMixin() { super(Component.empty()); }

    @Unique private record StageHit(int x, int y, int width, int height,
                                    BreakpointTarget target, boolean toggle) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    /** Vanilla's output controls occupy y=135..185 and Done sits below them. */
    @Unique private int codon$panelTop() { return height >= 260 ? height / 4 + 153 : 99; }
    @Unique private int codon$panelHeight() { return height >= 260
        ? Math.min(96, height - codon$panelTop() - 4) : 35; }
    @Unique private int codon$buttonsTop() { return codon$panelTop() + (height >= 260 ? 12 : 1); }
    @Unique private int codon$stagesTop() { return codon$panelTop() + (height >= 260 ? 33 : 21); }
    @Unique private boolean codon$showStageDetail() { return codon$panelHeight() >= 58; }
    @Unique private int codon$visibleStageRows() {
        int spare = codon$panelHeight() - (codon$stagesTop() - codon$panelTop())
            - (codon$showStageDetail() ? 16 : 3);
        return Math.max(1, spare / 12);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void codon$addBreakpointControls(CallbackInfo ci) {
        if (codon$entity() == null) return;
        int left = Math.max(4, (width - 300) / 2);
        int panelWidth = Math.min(300, width - 8);
        int blockWidth = Math.max(65, panelWidth * 34 / 100);
        int stageWidth = Math.max(50, panelWidth * 22 / 100);
        int conditionWidth = Math.max(50, panelWidth - blockWidth - stageWidth - 8);
        codon$blockButton = addRenderableWidget(Button.builder(Component.literal("○ " + codon$tr("codon.breakpoint.block_stop")),
            button -> codon$toggleBlock()).bounds(left, codon$buttonsTop(), blockWidth, 18).build());
        codon$stageButton = addRenderableWidget(Button.builder(Component.translatable("codon.breakpoint.stages"),
            button -> codon$focusStages()).bounds(left + blockWidth + 4, codon$buttonsTop(), stageWidth, 18).build());
        codon$conditionButton = addRenderableWidget(Button.builder(Component.translatable("codon.breakpoint.condition_action"),
            button -> codon$openCondition()).bounds(left + blockWidth + stageWidth + 8, codon$buttonsTop(),
                conditionWidth, 18).build());
    }

    @Unique private @Nullable CommandBlockEntity codon$entity() {
        Screen self = (Screen) (Object) this;
        return self instanceof CommandBlockEditScreen screen
            ? ((CommandBlockEditScreenAccessor) screen).codon$commandBlockEntity() : null;
    }

    @Unique private SourceLocation.@Nullable Block codon$location() {
        CommandBlockEntity entity = codon$entity();
        var level = Minecraft.getInstance().level;
        if (entity == null || level == null) return null;
        BlockPos pos = entity.getBlockPos();
        return new SourceLocation.Block(new BlockLocation(pos.getX(), pos.getY(), pos.getZ(),
            level.dimension().identifier().toString()));
    }

    @Unique private boolean codon$dirty() {
        return commandEdit == null || !commandEdit.getValue().equals(getCommandBlock().getCommand());
    }

    @Unique private void codon$updatePreview(SourceLocation.Block location) {
        if (codon$dirty()) return;
        String saved = getCommandBlock().getCommand();
        if (!saved.equals(codon$requestedCommand)) {
            codon$requestedCommand = saved;
            codon$selected = BreakpointTarget.whole(location);
            codon$scrollRow = 0;
            ClientDebuggerState state = CodonClientMod.state();
            if (state != null) ClientNetworking.requestStagePreview(state, location);
        }
    }

    /** Immediate breakpoint edits need the integrated server to keep processing packets. */
    @Override public boolean isPauseScreen() {
        return codon$entity() == null && super.isPauseScreen();
    }

    @Unique private void codon$toggleBlock() {
        SourceLocation.Block location = codon$location();
        if (location == null) return;
        codon$selected = BreakpointTarget.whole(location);
        codon$toggle(codon$selected);
    }

    @Unique private void codon$toggle(BreakpointTarget target) {
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null) return;
        BreakpointDefinition existing = state.breakpoints().get(target);
        ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE,
            existing == null ? BreakpointDefinition.plain(target) : existing);
    }

    @Unique private void codon$focusStages() {
        SourceLocation.Block location = codon$location();
        if (location == null || codon$dirty()) return;
        ClientStagePreviewState.Preview preview = codon$readyPreview(location);
        if (preview == null || preview.spans().isEmpty()) return;
        codon$keyboardStages = true;
        if (codon$selected == null || codon$selected.wholeCommand()) {
            codon$selected = BreakpointTarget.stage(location, preview.spans().getFirst().index(), preview.savedCommand());
        }
        setFocused(codon$stageButton);
    }

    @Unique private void codon$openCondition() {
        SourceLocation.Block location = codon$location();
        ClientDebuggerState state = CodonClientMod.state();
        if (location == null || state == null) return;
        BreakpointTarget target = codon$selected == null ? BreakpointTarget.whole(location) : codon$selected;
        if (codon$dirty()) return;
        BreakpointDefinition existing = state.breakpoints().get(target);
        BreakpointConditionScreen.Anchor anchor = codon$conditionButton == null ? null
            : new BreakpointConditionScreen.Anchor(codon$conditionButton.getX(), codon$conditionButton.getY(),
                codon$conditionButton.getWidth(), codon$conditionButton.getHeight());
        Minecraft.getInstance().gui.setScreen(new BreakpointConditionScreen((Screen) (Object) this, state,
            existing == null ? BreakpointDefinition.plain(target) : existing, anchor));
    }

    @Unique private ClientStagePreviewState.@Nullable Preview codon$readyPreview(SourceLocation.Block location) {
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null) return null;
        ClientStagePreviewState.Preview preview = state.stagePreviews().get(location);
        return preview != null && preview.status() == ClientStagePreviewState.Status.READY
            && preview.savedCommand().equals(getCommandBlock().getCommand()) ? preview : null;
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void codon$renderBreakpoints(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                         CallbackInfo ci) {
        SourceLocation.Block location = codon$location();
        if (location == null) return;
        codon$updatePreview(location);
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null || codon$blockButton == null || codon$stageButton == null || codon$conditionButton == null)
            return;
        BreakpointTarget whole = BreakpointTarget.whole(location);
        BreakpointDefinition block = state.breakpoints().get(whole);
        codon$blockButton.setMessage(Component.literal(BreakpointUi.glyph(block) + " " + codon$tr("codon.breakpoint.block_stop")));
        codon$blockButton.setTooltip(Tooltip.create(Component.literal(codon$tr("codon.breakpoint.block_stop") + " · "
            + location.block().x() + "," + location.block().y() + "," + location.block().z())));
        codon$blockButton.active = doneButton != null && doneButton.active && !state.breakpoints().pending(whole);
        if (codon$selected == null || !codon$selected.location().equals(location)) codon$selected = whole;
        BreakpointDefinition selected = state.breakpoints().get(codon$selected);
        codon$stageButton.setMessage(Component.literal(codon$selected.wholeCommand()
            ? codon$tr("codon.breakpoint.stages")
            : codon$tr("codon.breakpoint.stage_target", codon$selected.stageIndex() + 1)));
        ClientStagePreviewState.Preview preview = codon$readyPreview(location);
        codon$stageButton.active = !codon$dirty() && preview != null && !preview.spans().isEmpty();
        codon$conditionButton.setMessage(Component.literal(selected == null ? codon$tr("codon.breakpoint.set_condition")
            : BreakpointUi.condition(selected.condition())));
        var error = state.breakpoints().error(codon$selected);
        codon$conditionButton.setTooltip(Tooltip.create(Component.literal((selected == null
            ? codon$tr("codon.breakpoint.create_with_condition") : BreakpointUi.condition(selected.condition()))
            + (error == null ? "" : "\n" + codon$tr("codon.breakpoint.error."
                + error.name().toLowerCase(java.util.Locale.ROOT))))));
        codon$conditionButton.active = doneButton != null && doneButton.active
            && !codon$dirty()
            && !state.breakpoints().pending(codon$selected);

        int left = Math.max(4, (width - 300) / 2);
        int panelWidth = Math.min(300, width - 8);
        if (height >= 260) graphics.text(font, Component.literal(codon$tr("codon.breakpoint.debugging") + " · "
            + location.block().x() + "," + location.block().y() + "," + location.block().z()),
            left + 2, codon$panelTop() + 1, DebuggerTheme.MUTED);
        int stageTop = codon$stagesTop();
        int stageBottom = codon$panelTop() + codon$panelHeight();
        graphics.fill(left, stageTop - 2, left + panelWidth, stageBottom,
            DebuggerTheme.color(DebuggerTheme.SURFACE));
        graphics.outline(left, stageTop - 2, panelWidth, stageBottom - stageTop + 2,
            DebuggerTheme.color(DebuggerTheme.BORDER));
        codon$stageHits.clear();
        if (codon$dirty()) {
            graphics.text(font, Component.translatable("codon.breakpoint.save_command_first"), left + 5, stageTop + 1,
                DebuggerTheme.MUTED);
        } else if (preview == null) {
            ClientStagePreviewState.Preview last = state.stagePreviews().get(location);
            String message = last == null || last.status() == ClientStagePreviewState.Status.LOADING
                ? codon$tr("codon.breakpoint.loading_stages") : codon$tr("codon.breakpoint.stages_unavailable");
            graphics.text(font, Component.literal(message), left + 5, stageTop + 1, DebuggerTheme.MUTED);
        } else {
            codon$renderStages(graphics, state, location, preview, mouseX, mouseY, left + 4, left + panelWidth - 4);
        }
        if (codon$showStageDetail()) {
            String detail = error != null ? codon$tr("codon.breakpoint.error."
                + error.name().toLowerCase(java.util.Locale.ROOT))
                : selected == null ? codon$tr("codon.breakpoint.select_stage") : BreakpointUi.condition(selected.condition());
            graphics.text(font, Component.literal(detail), left + 5, stageBottom - 12,
                error == null ? DebuggerTheme.MUTED : DebuggerTheme.AMBER);
        }
    }

    @Unique private void codon$renderStages(GuiGraphicsExtractor graphics, ClientDebuggerState state,
                                            SourceLocation.Block location, ClientStagePreviewState.Preview preview,
                                            int mouseX, int mouseY, int left, int right) {
        int row = 0;
        int x = left;
        int previousEnd = 0;
        String command = preview.savedCommand();
        int prefixEnd = CommandFlowLayout.executePrefixEnd(command);
        if (prefixEnd > 0 && !preview.spans().isEmpty()) {
            String prefix = command.substring(0, prefixEnd);
            if (row >= codon$scrollRow && row < codon$scrollRow + codon$visibleStageRows())
                graphics.text(font, Component.literal(prefix), x,
                    codon$stagesTop() + (row - codon$scrollRow) * 12 + 1, DebuggerTheme.TEXT);
            x += font.width(prefix);
        }
        boolean firstStage = true;
        for (ClientStagePreviewState.StageSpan span : preview.spans()) {
            int start = Math.max(Math.max(previousEnd, span.start()), prefixEnd);
            int end = Math.max(start, span.end());
            previousEnd = end;
            if (start >= end || end > command.length()) continue;
            String fragment = command.substring(start, end).trim();
            if (fragment.isEmpty()) continue;
            BreakpointTarget target = BreakpointTarget.stage(location, span.index(), command);
            BreakpointDefinition definition = state.breakpoints().get(target);
            int cursor = 0;
            boolean first = true;
            while (cursor < fragment.length()) {
                int lead = first ? 16 : 12;
                int wholeWidth = font.width(fragment.substring(cursor)) + lead + 5;
                if (x > left && x + wholeWidth > right && !(firstStage && first)) { row++; x = left; }
                int capacity = Math.max(1, right - x - lead - 3);
                String piece = font.plainSubstrByWidth(fragment.substring(cursor), capacity);
                if (piece.isEmpty()) piece = fragment.substring(cursor, cursor + 1);
                int pieceWidth = Math.min(capacity, font.width(piece));
                if (row >= codon$scrollRow && row < codon$scrollRow + codon$visibleStageRows()) {
                    int y = codon$stagesTop() + (row - codon$scrollRow) * 12;
                    int boxWidth = Math.min(right - x, lead + pieceWidth + 3);
                    boolean hovered = mouseX >= x && mouseX < x + boxWidth && mouseY >= y && mouseY < y + 11;
                    boolean selected = target.equals(codon$selected);
                    graphics.fill(x, y, x + boxWidth, y + 11,
                        DebuggerTheme.color(selected ? DebuggerTheme.TEAL_SURFACE
                            : hovered ? DebuggerTheme.RAISED : DebuggerTheme.SURFACE));
                    if (first) {
                        // The empty marker is a hover affordance; saved breakpoints remain visible.
                        if (definition != null || hovered) {
                            graphics.text(font, Component.literal(BreakpointUi.glyph(definition)), x + 2, y + 1,
                                definition != null && definition.enabled() ? DebuggerTheme.RED : DebuggerTheme.MUTED);
                        }
                        codon$stageHits.add(new StageHit(x, y, 14, 11, target, true));
                    }
                    graphics.text(font, Component.literal(piece), x + lead, y + 1, DebuggerTheme.TEXT);
                    codon$stageHits.add(new StageHit(x + lead, y, Math.max(1, boxWidth - lead), 11, target, false));
                }
                cursor += piece.length();
                x += lead + pieceWidth + 5;
                first = false;
                if (cursor < fragment.length()) { row++; x = left; }
            }
            firstStage = false;
        }
        codon$totalRows = row + 1;
        codon$scrollRow = Math.clamp(codon$scrollRow, 0,
            Math.max(0, codon$totalRows - codon$visibleStageRows()));
        if (codon$totalRows > codon$visibleStageRows()) graphics.text(font, Component.literal("↕"),
            right - 9, codon$stagesTop() + (codon$visibleStageRows() - 1) * 12 + 1, DebuggerTheme.MUTED);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void codon$clickStage(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (codon$entity() == null || codon$dirty()) return;
        for (StageHit hit : codon$stageHits) {
            if (!hit.contains(event.x(), event.y())) continue;
            codon$selected = hit.target();
            codon$keyboardStages = true;
            if (codon$stageButton != null) setFocused(codon$stageButton);
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hit.toggle()) codon$toggle(hit.target());
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) codon$openCondition();
            cir.setReturnValue(true);
            return;
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void codon$scrollStages(double mouseX, double mouseY, double scrollX, double scrollY,
                                    CallbackInfoReturnable<Boolean> cir) {
        int left = Math.max(4, (width - 300) / 2);
        if (codon$entity() == null || mouseX < left || mouseX >= left + Math.min(300, width - 8)
            || mouseY < codon$stagesTop() - 2 || mouseY >= codon$panelTop() + codon$panelHeight()
            || codon$totalRows <= codon$visibleStageRows()) return;
        codon$scrollRow = Math.clamp(codon$scrollRow - (int) Math.signum(scrollY), 0,
            Math.max(0, codon$totalRows - codon$visibleStageRows()));
        cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void codon$stageKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!codon$keyboardStages || codon$entity() == null || codon$dirty()) return;
        if (getFocused() != codon$stageButton) { codon$keyboardStages = false; return; }
        if (event.key() == InputConstants.KEY_TAB) { codon$keyboardStages = false; return; }
        SourceLocation.Block location = codon$location();
        if (location == null) return;
        ClientStagePreviewState.Preview preview = codon$readyPreview(location);
        if (preview == null || preview.spans().isEmpty()) return;
        if (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT) {
            int current = codon$selected == null || codon$selected.wholeCommand() ? 0 : codon$selected.stageIndex();
            int next = Math.clamp(current + (event.key() == InputConstants.KEY_RIGHT ? 1 : -1),
                0, preview.spans().size() - 1);
            codon$selected = BreakpointTarget.stage(location, preview.spans().get(next).index(),
                preview.savedCommand());
            cir.setReturnValue(true);
        } else if (event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_RETURN
            || event.key() == InputConstants.KEY_NUMPADENTER) {
            if (codon$selected != null && !codon$selected.wholeCommand()) codon$toggle(codon$selected);
            cir.setReturnValue(true);
        }
    }

    @Unique private static String codon$tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
