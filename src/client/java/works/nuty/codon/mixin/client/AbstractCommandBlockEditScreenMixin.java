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
import works.nuty.codon.client.ui.WrappedCommandEditBox;
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
    @Unique private @Nullable BreakpointTarget codon$selected;

    protected AbstractCommandBlockEditScreenMixin() { super(Component.empty()); }

    @Inject(method = "init", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/EditBox;setMaxLength(I)V", ordinal = 0))
    private void codon$wrapCommandInput(CallbackInfo ci) {
        if (codon$entity() == null) return;
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
            return;
        }
        if (!command.equals(codon$requestedCommand)) {
            codon$requestedCommand = command;
            ClientNetworking.requestStagePreview(state, location);
        }
        BreakpointTarget whole = BreakpointTarget.whole(location);
        if (codon$selected != null && !codon$selected.location().equals(location)) codon$selected = null;
        List<WrappedCommandEditBox.Marker> markers = new ArrayList<>();
        markers.add(new WrappedCommandEditBox.Marker(whole, -1, -1, state.breakpoints().get(whole),
            whole.equals(codon$selected)));
        var preview = state.stagePreviews().get(location);
        if (preview != null && preview.status() == ClientStagePreviewState.Status.READY
                && command.equals(preview.savedCommand())) {
            int prefixEnd = CommandFlowLayout.executePrefixEnd(command);
            for (var span : preview.spans()) {
                int start = Math.max(prefixEnd, span.start());
                while (start < span.end() && Character.isWhitespace(command.charAt(start))) start++;
                if (start >= span.end() || span.end() > command.length()) continue;
                BreakpointTarget target = BreakpointTarget.stage(location, span.index(), command);
                markers.add(new WrappedCommandEditBox.Marker(target, start, span.end(), state.breakpoints().get(target),
                    target.equals(codon$selected)));
            }
        }
        editor.setBreakpointMarkers(command, markers);
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
        if (marker == null) {
            codon$selected = null;
            return;
        }
        if (state == null) return;
        cir.setReturnValue(true);
        codon$selected = marker.target();
        if (state.breakpoints().pending(marker.target())) return;
        BreakpointDefinition definition = state.breakpoints().get(marker.target());
        if (definition == null) definition = BreakpointDefinition.plain(marker.target());
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            ClientNetworking.sendBreakpointEdit(state, ClientBreakpointState.Action.TOGGLE, definition);
        } else {
            var point = editor.markerPosition(marker);
            var anchor = new BreakpointConditionScreen.Anchor(point.x() - 2, point.y() - 2, 5, 5);
            Minecraft.getInstance().gui.setScreen(new BreakpointConditionScreen((Screen) (Object) this, state,
                definition, anchor));
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void codon$scrollInput(double x, double y, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (commandEdit instanceof WrappedCommandEditBox && commandEdit.mouseScrolled(x, y, scrollX, scrollY))
            cir.setReturnValue(true);
    }
}
