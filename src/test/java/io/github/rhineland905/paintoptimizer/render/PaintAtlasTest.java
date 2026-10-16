package io.github.rhineland905.paintoptimizer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The memory limit and the eviction order, without a GPU: pages are counted, not created. */
class PaintAtlasTest {
	/** 64x64 cells: 64 of them per 512x512 page. */
	private static final int PER_PAGE = 64;

	private final FakeBackend backend = new FakeBackend();

	@Test
	void neverHoldsMorePagesThanTheLimit() {
		PaintAtlas atlas = new PaintAtlas(this.backend, 3);
		long frame = 10;
		for (int i = 0; i < 1000; i++) {
			assertNotNull(atlas.put("p" + i, canvas(64, 64), frame), "painting " + i);
			frame += 3;
			assertTrue(atlas.pageCount() <= 3);
			assertTrue(this.backend.live.size() <= 3);
		}

		assertEquals(3 * PaintAtlas.PAGE_BYTES, atlas.bytes());
		assertEquals(3 * PER_PAGE, atlas.paintingCount());
		assertEquals(1000 - 3 * PER_PAGE, atlas.evictions());
	}

	@Test
	void evictsThePaintingSeenLongestAgo() {
		PaintAtlas atlas = new PaintAtlas(this.backend, 1);
		List<BakedPaint> paints = new ArrayList<>();
		for (int i = 0; i < PER_PAGE; i++) {
			paints.add(atlas.put("p" + i, canvas(64, 64), i));
		}

		// Looking at the first painting again makes the second one the oldest.
		atlas.touch(paints.get(0), 100);
		BakedPaint newcomer = atlas.put("new", canvas(64, 64), 200);
		assertNotNull(newcomer);
		assertTrue(paints.get(0).isValid());
		assertFalse(paints.get(1).isValid());
		assertNull(atlas.get("p1"));
		assertSame(newcomer, atlas.get("new"));
		// The newcomer took over the evicted cell.
		assertEquals(paints.get(1).x(), newcomer.x());
		assertEquals(paints.get(1).y(), newcomer.y());
	}

	@Test
	void neverEvictsWhatIsOnScreen() {
		PaintAtlas atlas = new PaintAtlas(this.backend, 1);
		long frame = 50;
		for (int i = 0; i < PER_PAGE; i++) {
			atlas.put("p" + i, canvas(64, 64), frame);
		}

		// Everything was drawn this frame: the newcomer is turned away instead of evicting one.
		assertNull(atlas.put("new", canvas(64, 64), frame));
		// ...and in the next frame too.
		assertNull(atlas.put("new", canvas(64, 64), frame + 1));
		assertEquals(0, atlas.evictions());
		// Two frames later they are no longer on screen.
		assertNotNull(atlas.put("new", canvas(64, 64), frame + 2));
		assertEquals(1, atlas.evictions());
	}

	@Test
	void handsAStalePageToAnotherSizeClass() {
		PaintAtlas atlas = new PaintAtlas(this.backend, 2);
		// A full page of 64x32 paintings, seen long ago.
		for (int i = 0; i < 128; i++) {
			atlas.put("wide" + i, canvas(64, 32), 1);
		}

		// A page of square ones, seen recently, then one more square one.
		for (int i = 0; i < PER_PAGE; i++) {
			atlas.put("square" + i, canvas(64, 64), 100 + i);
		}

		assertEquals(2, atlas.pageCount());
		assertNotNull(atlas.put("square-new", canvas(64, 64), 500));
		// The square class would otherwise have evicted its own (newer) paintings; the stale page went instead.
		assertEquals(2, atlas.pageCount());
		assertNull(atlas.get("wide0"));
		assertNotNull(atlas.get("square0"));
		assertEquals(PER_PAGE + 1, atlas.paintingCount());
	}

	@Test
	void shrinkingTheLimitFreesPagesAtOnce() {
		PaintAtlas atlas = new PaintAtlas(this.backend, 8);
		for (int i = 0; i < 8 * PER_PAGE; i++) {
			atlas.put("p" + i, canvas(64, 64), i);
		}

		assertEquals(8, atlas.pageCount());
		atlas.setLimitPages(2);
		assertEquals(2, atlas.pageCount());
		assertEquals(2, this.backend.live.size());
		// The two pages kept are the most recently seen ones.
		assertNotNull(atlas.get("p" + (8 * PER_PAGE - 1)));
		assertNull(atlas.get("p0"));
	}

	@Test
	void sharesCellsBetweenSizeClassesOnlyThroughWholePages() {
		assertEquals(16, PaintAtlas.cellSide(1));
		assertEquals(16, PaintAtlas.cellSide(16));
		assertEquals(32, PaintAtlas.cellSide(17));
		assertEquals(64, PaintAtlas.cellSide(64));
		assertEquals(128, PaintAtlas.cellSide(65));
		assertEquals(512, PaintAtlas.cellSide(512));

		PaintAtlas atlas = new PaintAtlas(this.backend, 4);
		BakedPaint a = atlas.put("a", canvas(64, 64), 1);
		BakedPaint b = atlas.put("b", canvas(64, 16), 1);
		BakedPaint c = atlas.put("c", canvas(48, 64), 1);
		assertSame(a.page(), c.page());
		assertFalse(a.page() == b.page());
		assertEquals(2, atlas.pageCount());
	}

	@Test
	void texturesCoordinatesStayInsideTheCell() {
		PaintAtlas atlas = new PaintAtlas(this.backend, 1);
		Set<String> cells = new HashSet<>();
		for (int i = 0; i < PER_PAGE; i++) {
			BakedPaint paint = atlas.put("p" + i, canvas(64, 48), 1);
			assertTrue(cells.add(paint.x() + "," + paint.y()), "two paintings share a cell");
			assertTrue(paint.u0() > paint.x() / 512f && paint.u1() < (paint.x() + 64) / 512f);
			assertTrue(paint.v0() > paint.y() / 512f && paint.v1() < (paint.y() + 48) / 512f);
			assertTrue(paint.v1() > (paint.y() + 47) / 512f);
		}
	}

	@Test
	void clearFreesEverything() {
		PaintAtlas atlas = new PaintAtlas(this.backend, 4);
		BakedPaint paint = atlas.put("a", canvas(64, 64), 1);
		atlas.put("b", canvas(16, 16), 1);
		atlas.clear();
		assertFalse(paint.isValid());
		assertEquals(0, atlas.pageCount());
		assertEquals(0, atlas.bytes());
		assertEquals(0, this.backend.live.size());
		assertNotNull(atlas.put("a", canvas(64, 64), 2));
	}

	private static PaintCanvas canvas(int width, int height) {
		return new PaintCanvas(width, height, new int[width * height]);
	}

	private static final class FakeBackend implements PaintAtlas.Backend {
		final Set<Integer> live = new HashSet<>();

		@Override
		public Object createPage(int index) {
			assertTrue(this.live.add(index), "page index " + index + " handed out twice");
			return index;
		}

		@Override
		public void upload(PaintAtlas.Page page, int x, int y, PaintCanvas canvas) {
			assertTrue(this.live.contains(page.index()));
			assertTrue(x + canvas.width() <= PaintAtlas.PAGE_SIZE && y + canvas.height() <= PaintAtlas.PAGE_SIZE);
		}

		@Override
		public void releasePage(PaintAtlas.Page page) {
			assertTrue(this.live.remove(page.index()));
		}
	}
}
