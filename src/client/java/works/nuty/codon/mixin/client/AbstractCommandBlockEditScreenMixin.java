package works.nuty.codon.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
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
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.ui.BreakpointConditionScreen;
import works.nuty.codon.client.ui.ScreenLayers;
import works.nuty.codon.client.ui.WrappedCommandEditBox;
import works.nuty.codon.client.ui.InlineBreakpointButton;
import works.nuty.codon.client.ui.layout.CommandFlowLayout;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;

/** Places authoritative stage breakpoints directly in the editable command. */
@Mixin(AbstractCommandBlockEditScreen.class)
public abstract class AbstractCommandBlockEditScreenMixin extends Screen {
    @Shadow protected EditBox commandEdit;
    @Shadow protected Button doneButton;
    @Shadow protected abstract BaseCommandBlock getCommandBlock();
    @Unique private @Nullable String codon$requestedCommand;
    @Unique private final List<InlineBreakpointButton> codon$markerControls = new ArrayList<>();

    protected AbstractCommandBlockEditScreenMixin() { super(Component.empty()); }

    @Inject(method = "init", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/EditBox;setMaxLength(I)V", ordinal = 0))
    private void codon$wrapCommandInput(CallbackInfo ci) {
        if (codon$entity() == null) return;
        codon$markerControls.clear();
        commandEdit = new WrappedCommandEditBox(font, commandEdit.getX(), commandEdit.getY(),
            commandEdit.getWidth(), 60, commandEdit.getMessage());
        commandEdit.setMaxLength(32500);
    }

    @Unique private @Nullable CommandBlockEntity codon$entity() {
        return (Screen) (Object) this instanceof CommandBlockEditScreen screen
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

    /** Immediate breakpoint edits need the integrated server to keep processing packets. */
    @Override public boolean isPauseScreen() { return codon$entity() == null && super.isPauseScreen(); }

    @Unique private void codon$refreshMarkers() {
        if (!(commandEdit instanceof WrappedCommandEditBox editor)) return;
        SourceLocation.Block location = codon$location();
        var state = CodonClientMod.state();
        String command = editor.getValue();
        if (location == null || state == null || doneButton == null || !doneButton.active
                || !command.equals(getCommandBlock().getCommand())) {
            editor.setBreakpointMarkers(command, List.of());
            codon$syncMarkerControls(editor, List.of());
            return;
        }
        if (!command.equals(codon$requestedCommand)) {
            codon$requestedCommand = command;
            ClientNetworking.requestStagePreview(state, location);
        }
        BreakpointTarget whole = BreakpointTarget.whole(location);
        List<WrappedCommandEditBox.Marker> markers = new ArrayList<>();
        markers.add(new WrappedCommandEditBox.Marker(whole, -1, -1, state.breakpoints().get(whole)));
        var preview = state.stagePreviews().get(location);
        if (preview != null && preview.status() == ClientStagePreviewState.Status.READY
                && command.equals(preview.savedCommand())) {
            int prefixEnd = CommandFlowLayout.executePrefixEnd(command);
            for (var span : preview.spans()) {
                int start = Math.max(prefixEnd, span.start());
                while (start < span.end() && Character.isWhitespace(command.charAt(start))) start++;
                if (start >= span.end() || span.end() > command.length()) continue;
                BreakpointTarget target = BreakpointTarget.stage(location, span.index(), command);
                markers.add(new WrappedCommandEditBox.Marker(target, start, span.end(), state.breakpoints().get(target)));
            }
        }
        editor.setBreakpointMarkers(command, markers);
        codon$syncMarkerControls(editor, markers);
    }

    @Unique private void codon$syncMarkerControls(WrappedCommandEditBox editor,
                                                 List<WrappedCommandEditBox.Marker> markers) {
        if (!codon$markerControls.stream().map(InlineBreakpointButton::target).toList()
                .equals(markers.stream().map(WrappedCommandEditBox.Marker::target).toList())) {
            for (var control : codon$markerControls) {
                if (getFocused() == control) setFocused(editor);
                control.setFocused(false);
                removeWidget(control);
            }
            codon$markerControls.clear();
            for (var marker : markers) codon$markerControls.add(addRenderableWidget(
                new InlineBreakpointButton(editor, marker, condition -> codon$activateMarker(marker.target(), condition))));
        }
        var state = CodonClientMod.state();
        for (int index = 0; index < markers.size(); index++)
            codon$markerControls.get(index).update(markers.get(index), state == null
                || state.breakpoints().pending(markers.get(index).target()));
    }

    @Unique private void codon$activateMarker(BreakpointTarget target, boolean condition) {
        codon$refreshMarkers();
        if (!(commandEdit instanceof WrappedCommandEditBox)
                || codon$markerControls.stream().noneMatch(control -> control.target().equals(target))) return;
        var state = CodonClientMod.state();
        if (state == null || state.breakpoints().pending(target)) return;
        BreakpointDefinition definition = state.breakpoints().get(target);
        if (definition == null) definition = BreakpointDefinition.plain(target);
        if (!condition) {
            ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE, definition);
        } else {
            var control = codon$markerControls.stream().filter(value -> value.target().equals(target)).findFirst().orElseThrow();
            var anchor = new BreakpointConditionScreen.Anchor(control.getX(), control.getY(), 9, 9);
            ScreenLayers.open(this, new BreakpointConditionScreen(this, state,
                definition, anchor));
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void codon$prepareMarkers(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                     CallbackInfo ci) {
        codon$refreshMarkers();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void codon$clickMarker(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!(commandEdit instanceof WrappedCommandEditBox editor)
                || (event.button() != InputConstants.MOUSE_BUTTON_LEFT && event.button() != InputConstants.MOUSE_BUTTON_RIGHT)) return;
        codon$refreshMarkers();
        var marker = editor.markerAt(event.x(), event.y());
        var state = CodonClientMod.state();
        if (marker == null) return;
        if (state == null) return;
        cir.setReturnValue(true);
        codon$activateMarker(marker.target(), event.button() == InputConstants.MOUSE_BUTTON_RIGHT);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void codon$scrollInput(double x, double y, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (commandEdit instanceof WrappedCommandEditBox && commandEdit.mouseScrolled(x, y, scrollX, scrollY))
            cir.setReturnValue(true);
    }
}
