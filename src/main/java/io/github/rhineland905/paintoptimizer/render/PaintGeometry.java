package io.github.rhineland905.paintoptimizer.render;

import io.github.rhineland905.paintoptimizer.PaintOptimizer;
import net.minecraft.world.entity.Display;

/**
 * Checks, once per session, that our idea of a canvas's size matches the one vanilla measured.
 *
 * <p>The claim that one MC Paint pixel is exactly ten font units rests on the advances declared in
 * the resource pack. That is true of every version shipped so far, but it is a pack MC Paint can
 * change without telling us, and if it ever does, every painting would quietly render at the wrong
 * size. So the first canvas of a session is laid out by vanilla as well and the two answers are
 * compared: one painting's worth of work buys the right to skip that work for all the rest, and a
 * disagreement switches the whole optimisation off rather than drawing something wrong.
 */
public final class PaintGeometry {
	private enum State { UNCHECKED, TRUSTED, MISMATCHED }

	private static State state = State.UNCHECKED;

	private PaintGeometry() {
	}

	/** True once the check has passed; until then vanilla's layout must be left alone. */
	public static boolean isTrusted() {
		return state == State.TRUSTED;
	}

	/** False if the optimisation has been switched off for this session by a failed check. */
	public static boolean isUsable() {
		return state != State.MISMATCHED;
	}

	/**
	 * Compares {@code layout}, which vanilla produced, against the painting we decoded.
	 *
	 * <p>Returns whether the caller may go ahead and draw the texture.
	 */
	public static boolean verify(Display.TextDisplay.CachedInfo layout, BakedPaint paint) {
		switch (state) {
			case TRUSTED -> {
				return true;
			}
			case MISMATCHED -> {
				return false;
			}
			default -> {
			}
		}

		if (layout == null || layout.lines().isEmpty()) {
			// Nothing to compare against yet; draw the texture and check on some later painting.
			return true;
		}

		if (layout.width() == paint.textWidth() && layout.lines().size() == paint.height()) {
			state = State.TRUSTED;
			PaintOptimizer.LOGGER.debug("Canvas geometry verified against vanilla layout");

			return true;
		}

		state = State.MISMATCHED;
		PaintOptimizer.LOGGER.warn(
				"MC Paint canvas geometry does not match this resource pack: vanilla lays out {}x{} font units, "
						+ "we decoded {}x{} pixels ({}x{} units). Falling back to vanilla rendering for this session.",
				layout.width(), layout.lines().size(),
				paint.width(), paint.height(),
				paint.textWidth(), paint.height() * PaintCanvas.PIXEL_SIZE
		);
		PaintCache.clear();

		return false;
	}

	/** Lets a resource reload or a manual reload give a changed pack another chance. */
	public static void reset() {
		state = State.UNCHECKED;
	}
}
