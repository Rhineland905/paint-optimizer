package io.github.rhineland905.paintoptimizer.render;

import io.github.rhineland905.paintoptimizer.config.PaintConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;

/** Decides, once per text change, whether a text display is an MC Paint canvas we can take over. */
public final class PaintBindings {
	/** A display whose text changes more often than this within {@link #VOLATILE_WINDOW_MILLIS} is being painted on. */
	private static final int VOLATILE_FLIPS = 3;
	private static final long VOLATILE_WINDOW_MILLIS = 5000L;

	private PaintBindings() {
	}

	public static PaintBinding resolve(Display.TextDisplay display) {
		Display.TextDisplay.TextRenderState state = display.textRenderState();

		if (state == null) {
			return vanilla();
		}

		Component text = state.text();
		PaintHolder holder = (PaintHolder)display;
		PaintBinding cached = holder.paintoptimizer$binding();
		Component source = holder.paintoptimizer$bindingSource();

		// The server hands out a new component object whenever the text changes, so identity is a
		// free and exact invalidation check. A binding from before the cache was dropped, or whose
		// painting has since been evicted, is re-resolved rather than trusted.
		if (cached != null
				&& cached.isCurrent()
				&& source == text
				&& (!cached.replacesRendering() || cached.isReady())) {
			if (cached.isReady()) {
				PaintCache.touch(cached.paint());
			}

			return cached;
		}

		if (!PaintConfig.get().enabled || !PaintGeometry.isUsable()) {
			return bind(holder, text, vanilla());
		}

		PaintFont font = PaintFont.of(text.getStyle());

		if (font == null || !isReproducible(state)) {
			return bind(holder, text, vanilla());
		}

		// Deliberately left unbound while volatile, so the next frame asks again and the display
		// picks itself back up as soon as it stops changing.
		if (holder.paintoptimizer$volatility().isVolatile(
				System.currentTimeMillis(), source != null && source != text, VOLATILE_FLIPS, VOLATILE_WINDOW_MILLIS)) {
			if (holder.paintoptimizer$volatility().justSettledOnVanilla()) {
				holder.paintoptimizer$releaseLayout();
			}

			return vanilla();
		}

		PaintCache.Result result = PaintCache.resolve(text);

		switch (result.status()) {
			case READY -> {
				BakedPaint paint = result.paint();

				// Vanilla would have wrapped a row this wide onto several lines, which is not a shape
				// one quad can stand in for. MC Paint sets line_width to 10000 and never hits this.
				if (paint.textWidth() > state.lineWidth()) {
					return bind(holder, text, vanilla());
				}

				return bind(holder, text, new PaintBinding(font, paint, PaintCache.generation(), 0));
			}
			case FULL -> {
				return bind(holder, text, new PaintBinding(null, null, PaintCache.generation(), PaintCache.frame() + PaintCache.RETRY_FRAMES));
			}
			case UNSUPPORTED -> {
				PaintCache.noteUnsupportedCanvas(text);

				return bind(holder, text, vanilla());
			}
			default -> {
				// Deliberately not remembered: the next frame has budget again.
				return new PaintBinding(font, null, PaintCache.generation(), 0);
			}
		}
	}

	/**
	 * Whether the display's own settings are ones a flat texture can reproduce.
	 *
	 * <p>A drop shadow is drawn per glyph, offset and darkened; there is no shadow of a picture to
	 * stand in for it. MC Paint never sets one, but another datapack could reuse its font.
	 */
	private static boolean isReproducible(Display.TextDisplay.TextRenderState state) {
		return (state.flags() & Display.TextDisplay.FLAG_SHADOW) == 0;
	}

	private static PaintBinding bind(PaintHolder holder, Component text, PaintBinding binding) {
		holder.paintoptimizer$setBinding(text, binding);

		return binding;
	}

	private static PaintBinding vanilla() {
		return new PaintBinding(null, null, PaintCache.generation(), 0);
	}
}
