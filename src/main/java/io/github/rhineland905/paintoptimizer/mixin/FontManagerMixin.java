package io.github.rhineland905.paintoptimizer.mixin;

import io.github.rhineland905.paintoptimizer.render.ClientHooks;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the canvas renderer when the fonts change.
 *
 * <p>Both hooks sit after the new fonts are in place. A hook at the start of a resource reload
 * would fire while the old fonts are still drawing, and anything measured in between would be
 * measured in fonts about to disappear.
 */
@Mixin(FontManager.class)
public class FontManagerMixin {
	@Inject(
			method = "apply(Lnet/minecraft/client/gui/font/FontManager$Preparation;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
			at = @At("TAIL")
	)
	private void paintoptimizer$onFontsLoaded(CallbackInfo ci) {
		ClientHooks.onFontsChanged();
	}

	@Inject(method = "updateOptions(Lnet/minecraft/client/Options;)V", at = @At("TAIL"))
	private void paintoptimizer$onFontOptionsChanged(CallbackInfo ci) {
		ClientHooks.onFontsChanged();
	}
}
