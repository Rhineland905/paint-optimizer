package io.github.rhineland905.paintoptimizer.render;

import org.jspecify.annotations.Nullable;

/**
 * What the mod has decided to do about one text display.
 *
 * <p>Three states, distinguished by which fields are set: no font means hands off, a font with no
 * painting means the canvas is recognised but not baked yet, and both set means draw the painting
 * instead of the text.
 *
 * <p>{@code generation} is what lets a decision be un-made. A binding is only trusted while it
 * matches {@link PaintCache#generation()}, so dropping the cache or flipping the switch retires every
 * decision in the world at once, including the ones that said to leave a display alone. A non-zero
 * {@code retryFrame} makes a decision temporary: a painting that found the cache full is drawn the
 * vanilla way until then, and asks again after.
 */
public record PaintBinding(@Nullable PaintFont font, @Nullable BakedPaint paint, long generation, long retryFrame) {
	/** Stand-in for a render state nothing has been extracted into yet; never matches a generation. */
	public static final PaintBinding NONE = new PaintBinding(null, null, Long.MIN_VALUE, 0);

	/** True once we have committed to drawing this display ourselves, baked or not. */
	public boolean replacesRendering() {
		return this.font != null;
	}

	public boolean isReady() {
		return this.paint != null && this.paint.isValid();
	}

	public boolean isCurrent() {
		return this.generation == PaintCache.generation() && (this.retryFrame == 0 || PaintCache.frame() < this.retryFrame);
	}
}
