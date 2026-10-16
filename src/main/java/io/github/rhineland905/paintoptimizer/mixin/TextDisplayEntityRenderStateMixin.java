package io.github.rhineland905.paintoptimizer.mixin;

import io.github.rhineland905.paintoptimizer.render.PaintBinding;
import io.github.rhineland905.paintoptimizer.render.PaintStateHolder;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Carries the binding from render state extraction, which sees the entity, to submission, which
 * only sees the render state.
 */
@Mixin(TextDisplayEntityRenderState.class)
public abstract class TextDisplayEntityRenderStateMixin implements PaintStateHolder {
	@Unique
	private PaintBinding paintoptimizer$binding = PaintBinding.NONE;

	@Override
	public PaintBinding paintoptimizer$binding() {
		return this.paintoptimizer$binding;
	}

	@Override
	public void paintoptimizer$setBinding(PaintBinding binding) {
		this.paintoptimizer$binding = binding;
	}
}
