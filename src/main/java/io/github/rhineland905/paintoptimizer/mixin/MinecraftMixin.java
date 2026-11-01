package io.github.rhineland905.paintoptimizer.mixin;

import io.github.rhineland905.paintoptimizer.render.ClientHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The canvas renderer's client lifecycle: one hook per frame. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "runTick(Z)V", at = @At("HEAD"))
	private void paintoptimizer$onFrameStart(boolean renderLevel, CallbackInfo ci) {
		ClientHooks.onFrameStart();
	}
}
