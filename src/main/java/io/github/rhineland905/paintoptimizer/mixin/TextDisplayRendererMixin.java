package io.github.rhineland905.paintoptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.rhineland905.paintoptimizer.render.PaintBinding;
import io.github.rhineland905.paintoptimizer.render.PaintBindings;
import io.github.rhineland905.paintoptimizer.render.PaintGeometry;
import io.github.rhineland905.paintoptimizer.render.PaintRenderer;
import io.github.rhineland905.paintoptimizer.render.PaintStateHolder;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces MC Paint canvases with their cached painting.
 *
 * <p>All three hooks sit on the text display renderer alone, which is a corner of the engine no
 * performance mod competes for: Sodium rewrites terrain and chunk building, not entity renderers,
 * so this coexists with it rather than fighting it.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public abstract class TextDisplayRendererMixin {
	@Unique
	private static final Display.TextDisplay.CachedInfo PAINTOPTIMIZER$NO_LAYOUT = new Display.TextDisplay.CachedInfo(List.of(), 0);

	/**
	 * Skips vanilla's line splitting for canvases we are about to replace.
	 *
	 * <p>The split walks every glyph in the component, and the component for one painting holds
	 * thousands, so this is the burst of work that hitches when a district of paintings streams in.
	 * Resolving here rather than later is deliberate: the decode happens before anything downstream
	 * can depend on a layout we are not going to compute.
	 */
	@WrapOperation(
			method = "extractRenderState(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/Display$TextDisplay;cacheDisplay(Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"
			)
	)
	private Display.TextDisplay.CachedInfo paintoptimizer$skipLayoutForPaintings(
			Display.TextDisplay display, Display.TextDisplay.LineSplitter splitter, Operation<Display.TextDisplay.CachedInfo> original
	) {
		if (PaintGeometry.isTrusted() && PaintBindings.resolve(display).replacesRendering()) {
			return PAINTOPTIMIZER$NO_LAYOUT;
		}

		return original.call(display, splitter);
	}

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V",
			at = @At("TAIL")
	)
	private void paintoptimizer$bindPainting(
			Display.TextDisplay display, TextDisplayEntityRenderState state, float partialTick, CallbackInfo ci
	) {
		((PaintStateHolder)state).paintoptimizer$setBinding(PaintBindings.resolve(display));
	}

	@Inject(
			method = "submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void paintoptimizer$submitPainting(
			TextDisplayEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light, float partialTick, CallbackInfo ci
	) {
		PaintBinding binding = ((PaintStateHolder)state).paintoptimizer$binding();

		if (!binding.replacesRendering()) {
			return;
		}

		// A canvas that is recognised but not baked yet draws nothing for a frame or two. Falling
		// back to the text would mean paying the very cost we are here to avoid, in the frames where
		// the machine is already busiest.
		if (binding.isReady()) {
			if (!PaintGeometry.verify(state.cachedInfo, binding.paint())) {
				// The resource pack measures canvases differently than we do. Vanilla still has a
				// real layout here, because the check has to run before we are allowed to skip one.
				return;
			}

			PaintRenderer.submit(binding, state, poseStack, collector, light, partialTick);
		}

		ci.cancel();
	}
}
