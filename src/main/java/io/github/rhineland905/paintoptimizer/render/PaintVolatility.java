package io.github.rhineland905.paintoptimizer.render;

/**
 * Tracks how often one display's text changes, so a canvas being edited is left alone.
 *
 * <p>MC Paint's Studio rewrites its canvas every time a brush stroke lands. Baking a texture for
 * each of those would upload twenty throwaway textures a second and evict the real paintings to
 * make room — the one way this optimisation could make the game worse rather than merely fail to
 * help. A display that keeps changing is handed back to vanilla until it settles down, and picks
 * itself back up once it has.
 */
public final class PaintVolatility {
	private long windowStart;
	private int flips;
	private boolean active;

	/**
	 * Records that we are looking at this display again and says whether it is too busy to bake.
	 *
	 * @param textChanged whether the server has sent new text since we last looked
	 */
	boolean isVolatile(long now, boolean textChanged, int threshold, long windowMillis) {
		if (now - this.windowStart > windowMillis) {
			this.windowStart = now;
			this.flips = 0;
			this.active = false;
		}

		if (textChanged) {
			this.flips++;
		}

		return this.flips > threshold;
	}

	/** True only on the first call since this display became volatile. */
	boolean justSettledOnVanilla() {
		if (this.active) {
			return false;
		}

		this.active = true;

		return true;
	}
}
