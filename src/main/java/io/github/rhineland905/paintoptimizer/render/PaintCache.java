package io.github.rhineland905.paintoptimizer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.rhineland905.paintoptimizer.PaintOptimizer;
import io.github.rhineland905.paintoptimizer.config.PaintConfig;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Decoded paintings, keyed by content and shared by every entity that shows the same picture.
 *
 * <p>A city full of paintings is mostly the same few pictures repeated, so keying on content rather
 * than on entity collapses hundreds of copies onto a handful of cells.
 *
 * <p>Decoding and uploading happen inline on the render thread: a canvas is a few thousand pixels,
 * so the work is tens of microseconds, and a per-frame budget keeps a whole district streaming in at
 * once from turning into one long frame. Anything over budget waits for the next frame, during which
 * the painting is not drawn: a frame or two, not something a player notices.
 *
 * <p>Memory is the {@link PaintAtlas}'s job: it never holds more pages than the configured limit.
 */
public final class PaintCache {
	/** Decoding and uploading may take this long per frame. */
	private static final long BAKE_BUDGET_NANOS = 2_000_000L;
	/** Largest canvas taken over. MC Paint's own are at most 64x64. */
	public static final int MAX_CANVAS_PIXELS = 256 * 256;
	/** Frames a painting that found the cache full waits, drawn the vanilla way, before asking again. */
	public static final int RETRY_FRAMES = 20;

	private static final PaintAtlas ATLAS = new PaintAtlas(new GpuPages(), pagesFor(PaintConfig.get().cacheSizeMb));

	private static long frame;
	private static long frameSpentNs;
	private static long generation;
	private static boolean warnedUnsupported;
	private static volatile boolean clearRequested;
	private static volatile int requestedLimit = -1;

	private PaintCache() {
	}

	/** Called once per rendered frame, before anything is drawn, to reset the bake budget. */
	public static void onFrameStart() {
		frame++;
		frameSpentNs = 0;

		if (clearRequested) {
			clearRequested = false;
			ATLAS.clear();
		}

		int limit = requestedLimit;
		if (limit > 0) {
			requestedLimit = -1;
			ATLAS.setLimitPages(limit);
		}
	}

	public static long frame() {
		return frame;
	}

	/**
	 * Bumped whenever every decision made so far has to be reconsidered.
	 *
	 * <p>Entities cache what the mod decided about them, including the decision to leave them alone,
	 * and there is no list of those entities to walk. Comparing against this counter retires all of
	 * them at once instead.
	 */
	public static long generation() {
		return generation;
	}

	/** Retires every binding without dropping the textures; for a change of settings. */
	public static void invalidateBindings() {
		generation++;
	}

	/** What came of a {@link #resolve} call. */
	public enum Status {
		/** {@link Result#paint()} is ready to draw. */
		READY,
		/** Out of budget this frame; try again next frame. */
		PENDING,
		/** The cache is at its limit and everything in it is on screen; draw it the vanilla way for now. */
		FULL,
		/** Not something we can reproduce exactly. Render it the vanilla way, and stop asking. */
		UNSUPPORTED
	}

	public record Result(Status status, @Nullable BakedPaint paint) {
		static final Result PENDING = new Result(Status.PENDING, null);
		static final Result FULL = new Result(Status.FULL, null);
		static final Result UNSUPPORTED = new Result(Status.UNSUPPORTED, null);
	}

	/** The painting for {@code text}, decoding and uploading it if it is not cached. */
	public static Result resolve(Component text) {
		if (frameSpentNs >= BAKE_BUDGET_NANOS) {
			return Result.PENDING;
		}

		long start = System.nanoTime();

		try {
			PaintCanvas canvas = PaintDecoder.decode(text, MAX_CANVAS_PIXELS);

			if (canvas == null || canvas.width() > PaintAtlas.PAGE_SIZE || canvas.height() > PaintAtlas.PAGE_SIZE) {
				return Result.UNSUPPORTED;
			}

			String hash = canvas.hash();
			BakedPaint cached = ATLAS.get(hash);

			if (cached != null) {
				ATLAS.touch(cached, frame);
				return new Result(Status.READY, cached);
			}

			BakedPaint baked;

			try {
				baked = ATLAS.put(hash, canvas, frame);
			} catch (RuntimeException e) {
				PaintOptimizer.LOGGER.warn("Could not upload a {}x{} painting", canvas.width(), canvas.height(), e);
				return Result.UNSUPPORTED;
			}

			return baked == null ? Result.FULL : new Result(Status.READY, baked);
		} finally {
			frameSpentNs += System.nanoTime() - start;
		}
	}

	public static void touch(BakedPaint paint) {
		ATLAS.touch(paint, frame);
	}

	/**
	 * Warns once if a canvas font turns up wrapped around something we cannot read.
	 *
	 * <p>The encoding is MC Paint's to change. If it ever does, this line in the log is the
	 * difference between "the mod quietly stopped helping" and a bug report.
	 */
	public static void noteUnsupportedCanvas(Component text) {
		if (warnedUnsupported) {
			return;
		}

		warnedUnsupported = true;
		String sample = text.getString();
		PaintOptimizer.LOGGER.warn("A display using an MC Paint canvas font did not decode; it is drawn the vanilla way. "
			+ "If every canvas does this, the encoding has changed. First 80 characters: {}", sample.length() > 80 ? sample.substring(0, 80) : sample);
	}

	/** Applies the cache size from the config. Shrinking it frees pages straight away. */
	public static void applyLimit() {
		setLimitPages(pagesFor(PaintConfig.get().cacheSizeMb));
	}

	/** The limit in pages; {@link #applyLimit()} for the configured one. Off the render thread it applies next frame. */
	public static void setLimitPages(int pages) {
		if (RenderSystem.isOnRenderThread()) {
			ATLAS.setLimitPages(pages);
		} else {
			requestedLimit = Math.max(1, pages);
		}
	}

	private static int pagesFor(int megabytes) {
		return (int) Math.max(1, megabytes * 1024L * 1024L / PaintAtlas.PAGE_BYTES);
	}

	/**
	 * Drops every painting; used when leaving a world, switching the mod off, and by the button in
	 * the settings.
	 *
	 * <p>Freeing a texture talks to the GPU, so a call from anywhere but the render thread only
	 * retires the bindings and leaves the release to the next frame. Nothing new is drawn in the
	 * meantime either way.
	 */
	public static void clear() {
		invalidateBindings();

		if (RenderSystem.isOnRenderThread()) {
			ATLAS.clear();
		} else {
			clearRequested = true;
		}
	}

	/** Whether a painting with this content hash is cached. */
	public static boolean contains(String hash) {
		return ATLAS.get(hash) != null;
	}

	public static long usedBytes() {
		return ATLAS.bytes();
	}

	public static long limitBytes() {
		return ATLAS.limitPages() * PaintAtlas.PAGE_BYTES;
	}

	public static int paintingCount() {
		return ATLAS.paintingCount();
	}

	public static int pageCount() {
		return ATLAS.pageCount();
	}

	public static long evictions() {
		return ATLAS.evictions();
	}
}
