package io.github.rhineland905.paintoptimizer.render;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where baked paintings live: atlas pages of {@link #PAGE_SIZE}x{@link #PAGE_SIZE} pixels, each cut
 * into equal cells, and never more pages than the memory limit allows.
 *
 * <p>A page is the unit of memory: one RGBA texture of exactly {@link #PAGE_BYTES}. Every page holds
 * cells of a single size class, the canvas size rounded up to powers of two, so a freed cell always
 * fits the next painting of that class and the atlas cannot fragment. Sharing pages is also what
 * lets the game draw every painting on a page in one batch, instead of one draw per painting.
 *
 * <p>When a painting needs room and the limit is reached, the painting of its class that was drawn
 * least recently gives up its cell, unless a whole page has gone unseen for longer still, in which
 * case that page is emptied and handed over. Paintings drawn in the last two frames are never
 * evicted: when everything in the cache is on screen, the newcomer is turned away (and drawn the
 * vanilla way) rather than pushing out something visible that would push it back out next frame.
 *
 * <p>Holds no GPU state of its own; the textures belong to the {@link Backend}, which keeps the
 * bookkeeping testable without a game.
 */
public final class PaintAtlas {
	public static final int PAGE_SIZE = 512;
	public static final long PAGE_BYTES = (long) PAGE_SIZE * PAGE_SIZE * 4;
	/** Smallest cell side. A 1x1 canvas still takes a 16x16 cell, which is a kilobyte. */
	static final int MIN_CELL = 16;
	/** A painting drawn this frame or the last one counts as on screen and is not evicted. */
	private static final int PROTECTED_FRAMES = 2;

	/** Owns the textures behind the pages. */
	public interface Backend {
		/** Creates the texture for a new page; the result is kept in {@link Page#handle()}. */
		@Nullable Object createPage(int index);

		void upload(Page page, int x, int y, PaintCanvas canvas);

		void releasePage(Page page);
	}

	private final Backend backend;
	private final Map<String, BakedPaint> byHash = new HashMap<>();
	private final Map<Integer, SizeClass> classes = new HashMap<>();
	private final List<Page> pages = new ArrayList<>();
	/** Page indices in use. Reusing the lowest free one keeps texture ids, and the render types made for them, bounded. */
	private final BitSet indices = new BitSet();
	private int limitPages;
	private long evictions;

	public PaintAtlas(Backend backend, int limitPages) {
		this.backend = backend;
		this.limitPages = Math.max(1, limitPages);
	}

	public @Nullable BakedPaint get(String hash) {
		return this.byHash.get(hash);
	}

	/**
	 * Uploads a canvas into a free cell, making room if the limit is reached.
	 *
	 * @return the baked painting, or null when there is no room without evicting something on screen
	 */
	public @Nullable BakedPaint put(String hash, PaintCanvas canvas, long frame) {
		if (canvas.width() > PAGE_SIZE || canvas.height() > PAGE_SIZE) {
			throw new IllegalArgumentException("A " + canvas.width() + "x" + canvas.height() + " canvas does not fit a page");
		}

		SizeClass cls = this.classes.computeIfAbsent(classKey(canvas.width(), canvas.height()), SizeClass::new);
		Page page = this.allocate(cls, frame);
		if (page == null) {
			return null;
		}

		int cell = page.takeCell();
		BakedPaint paint = new BakedPaint(hash, canvas.width(), canvas.height(), page, cell);
		try {
			this.backend.upload(page, paint.x(), paint.y(), canvas);
		} catch (RuntimeException e) {
			page.returnCell(cell);
			throw e;
		}

		page.cells[cell] = paint;
		paint.lastUsed = frame;
		page.lastUsed = Math.max(page.lastUsed, frame);
		cls.append(paint);
		this.byHash.put(hash, paint);
		return paint;
	}

	/** Marks a painting as drawn this frame, which moves it to the back of the eviction queue. */
	public void touch(BakedPaint paint, long frame) {
		if (paint.lastUsed == frame || !paint.isValid()) {
			return;
		}

		paint.lastUsed = frame;
		paint.page().lastUsed = frame;
		SizeClass cls = paint.page().cls;
		cls.unlink(paint);
		cls.append(paint);
	}

	/** A page of {@code cls} with a free cell: an existing one, a new one, or one made free by eviction. */
	private @Nullable Page allocate(SizeClass cls, long frame) {
		for (Page page : cls.pages) {
			if (page.free > 0) {
				return page;
			}
		}

		if (this.pages.size() < this.limitPages) {
			return this.newPage(cls);
		}

		long newestEvictable = frame - PROTECTED_FRAMES;
		BakedPaint oldest = cls.head;
		if (oldest != null && oldest.lastUsed > newestEvictable) {
			oldest = null;
		}

		Page stalest = null;
		for (Page page : this.pages) {
			if (page.lastUsed <= newestEvictable && (stalest == null || page.lastUsed < stalest.lastUsed)) {
				stalest = page;
			}
		}

		// A whole page nobody has looked at for longer than this class's oldest painting is a better
		// victim than that painting: it frees memory a class that is no longer around is sitting on.
		if (stalest != null && (oldest == null || stalest.lastUsed < oldest.lastUsed)) {
			this.empty(stalest);
			if (stalest.cls == cls) {
				return stalest;
			}

			this.releasePage(stalest);
			return this.newPage(cls);
		}

		if (oldest != null) {
			this.evict(oldest);
			return oldest.page();
		}

		return null;
	}

	private Page newPage(SizeClass cls) {
		int index = this.indices.nextClearBit(0);
		this.indices.set(index);
		Page page = new Page(index, cls);
		try {
			page.handle = this.backend.createPage(index);
		} catch (RuntimeException e) {
			this.indices.clear(index);
			throw e;
		}

		this.pages.add(page);
		cls.pages.add(page);
		return page;
	}

	private void releasePage(Page page) {
		this.backend.releasePage(page);
		this.indices.clear(page.index);
		this.pages.remove(page);
		page.cls.pages.remove(page);
	}

	private void empty(Page page) {
		for (BakedPaint paint : page.cells) {
			if (paint != null) {
				this.evict(paint);
			}
		}
	}

	private void evict(BakedPaint paint) {
		Page page = paint.page();
		this.byHash.remove(paint.hash());
		page.cls.unlink(paint);
		page.cells[paint.cell()] = null;
		page.returnCell(paint.cell());
		paint.invalidate();
		this.evictions++;
	}

	/**
	 * Sets the most pages there may be, freeing the pages seen longest ago if there are more already.
	 * This is a hard limit, so it frees pages even if they are on screen.
	 */
	public void setLimitPages(int limit) {
		this.limitPages = Math.max(1, limit);
		while (this.pages.size() > this.limitPages) {
			Page stalest = this.pages.getFirst();
			for (Page page : this.pages) {
				if (page.lastUsed < stalest.lastUsed) {
					stalest = page;
				}
			}

			this.empty(stalest);
			this.releasePage(stalest);
		}
	}

	/** Drops everything. */
	public void clear() {
		for (Page page : List.copyOf(this.pages)) {
			for (BakedPaint paint : page.cells) {
				if (paint != null) {
					paint.invalidate();
				}
			}

			this.backend.releasePage(page);
		}

		this.pages.clear();
		this.classes.clear();
		this.byHash.clear();
		this.indices.clear();
	}

	public int limitPages() {
		return this.limitPages;
	}

	public int pageCount() {
		return this.pages.size();
	}

	public int paintingCount() {
		return this.byHash.size();
	}

	/** Texture memory the pages take, which is all the memory the cache holds on to. */
	public long bytes() {
		return this.pages.size() * PAGE_BYTES;
	}

	public long evictions() {
		return this.evictions;
	}

	static int cellSide(int pixels) {
		return Math.max(MIN_CELL, Integer.highestOneBit(Math.max(1, pixels - 1)) << 1);
	}

	private static int classKey(int width, int height) {
		return cellSide(width) << 16 | cellSide(height);
	}

	/** Paintings whose canvas rounds up to the same cell, with the pages they share and their eviction queue. */
	static final class SizeClass {
		final int cellWidth;
		final int cellHeight;
		final int columns;
		final int cellsPerPage;
		final List<Page> pages = new ArrayList<>();
		/** Least recently drawn first. */
		@Nullable BakedPaint head;
		@Nullable BakedPaint tail;

		SizeClass(int key) {
			this.cellWidth = key >>> 16;
			this.cellHeight = key & 0xFFFF;
			this.columns = PAGE_SIZE / this.cellWidth;
			this.cellsPerPage = this.columns * (PAGE_SIZE / this.cellHeight);
		}

		void append(BakedPaint paint) {
			paint.prev = this.tail;
			paint.next = null;
			if (this.tail != null) {
				this.tail.next = paint;
			} else {
				this.head = paint;
			}

			this.tail = paint;
		}

		void unlink(BakedPaint paint) {
			if (paint.prev != null) {
				paint.prev.next = paint.next;
			} else {
				this.head = paint.next;
			}

			if (paint.next != null) {
				paint.next.prev = paint.prev;
			} else {
				this.tail = paint.prev;
			}

			paint.prev = null;
			paint.next = null;
		}
	}

	/** One texture's worth of cells of one size class. */
	public static final class Page {
		final int index;
		final SizeClass cls;
		final @Nullable BakedPaint[] cells;
		private final int[] freeCells;
		int free;
		/** The last frame any painting on this page was drawn. */
		long lastUsed = Long.MIN_VALUE;
		@Nullable Object handle;

		Page(int index, SizeClass cls) {
			this.index = index;
			this.cls = cls;
			this.cells = new BakedPaint[cls.cellsPerPage];
			this.freeCells = new int[cls.cellsPerPage];
			for (int i = 0; i < cls.cellsPerPage; i++) {
				this.freeCells[i] = cls.cellsPerPage - 1 - i;
			}

			this.free = cls.cellsPerPage;
		}

		int takeCell() {
			return this.freeCells[--this.free];
		}

		void returnCell(int cell) {
			this.freeCells[this.free++] = cell;
		}

		public int index() {
			return this.index;
		}

		public @Nullable Object handle() {
			return this.handle;
		}

		int cellX(int cell) {
			return cell % this.cls.columns * this.cls.cellWidth;
		}

		int cellY(int cell) {
			return cell / this.cls.columns * this.cls.cellHeight;
		}
	}
}
