package io.github.rhineland905.paintoptimizer.mixin;

import io.github.rhineland905.paintoptimizer.render.PaintBinding;
import io.github.rhineland905.paintoptimizer.render.PaintHolder;
import io.github.rhineland905.paintoptimizer.render.PaintVolatility;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Gives every text display somewhere to remember what the mod decided about it. */
@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayMixin implements PaintHolder {
	@Shadow
	private Display.TextDisplay.CachedInfo clientDisplayCache;

	@Unique
	private PaintBinding paintoptimizer$binding;

	@Unique
	private Component paintoptimizer$bindingSource;

	@Unique
	private PaintVolatility paintoptimizer$volatility;

	@Override
	public PaintBinding paintoptimizer$binding() {
		return this.paintoptimizer$binding;
	}

	@Override
	public Component paintoptimizer$bindingSource() {
		return this.paintoptimizer$bindingSource;
	}

	@Override
	public void paintoptimizer$setBinding(Component source, PaintBinding binding) {
		// While the mod draws a display it also suppresses vanilla's line splitting, leaving an empty
		// layout behind. Handing the display back (the picture turned out to be one we cannot
		// reproduce, the cache was full, the optimisation was switched off) has to drop that empty
		// layout, or vanilla would faithfully render nothing. Taking a display over drops vanilla's
		// layout too: thousands of glyphs' worth of memory nothing is going to read.
		if (this.paintoptimizer$binding != null && this.paintoptimizer$binding.replacesRendering() != binding.replacesRendering()) {
			this.clientDisplayCache = null;
		}

		this.paintoptimizer$bindingSource = source;
		this.paintoptimizer$binding = binding;
	}

	@Override
	public PaintVolatility paintoptimizer$volatility() {
		if (this.paintoptimizer$volatility == null) {
			this.paintoptimizer$volatility = new PaintVolatility();
		}

		return this.paintoptimizer$volatility;
	}

	@Override
	public void paintoptimizer$releaseLayout() {
		this.clientDisplayCache = null;
	}
}
