package io.github.rhineland905.paintoptimizer.mixin;

import io.github.rhineland905.paintoptimizer.render.ClientHooks;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The canvas renderer's client lifecycle: one hook per frame, plus resource reloads. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "runTick(Z)V", at = @At("HEAD"))
	private void paintoptimizer$onFrameStart(boolean renderLevel, CallbackInfo ci) {
		ClientHooks.onFrameStart();
	}

	@Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
	private void paintoptimizer$onResourceReload(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		ClientHooks.onResourceReload();
	}
}
