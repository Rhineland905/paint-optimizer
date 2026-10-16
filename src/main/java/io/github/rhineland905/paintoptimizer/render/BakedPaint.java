package io.github.rhineland905.paintoptimizer.render;

import org.jspecify.annotations.Nullable;

/**
 * A painting that lives in a cell of a {@link PaintAtlas} page, drawn as one quad.
 *
 * <p>Only the size and the place are kept: the pixels are on the GPU, and keeping a second copy in
 * memory would double what the cache costs for nothing.
 */
public final class BakedPaint {
	/**
	 * How far the texture coordinates stay inside the cell, in texels. Nearest sampling at the very
	 * edge of a quad could otherwise round onto the next cell's pixels; a hundredth of a texel never
	 * changes which pixel the inside of the painting shows.
	 */
	private static final float INSET = 1f / 128f;

	private final String hash;
	private final int width;
	private final int height;
	private final PaintAtlas.Page page;
	private final int cell;
	private final int x;
	private final int y;
	private boolean valid = true;

	long lastUsed;
	@Nullable BakedPaint prev;
	@Nullable BakedPaint next;

	BakedPaint(String hash, int width, int height, PaintAtlas.Page page, int cell) {
		this.hash = hash;
		this.width = width;
		this.height = height;
		this.page = page;
		this.cell = cell;
		this.x = page.cellX(cell);
		this.y = page.cellY(cell);
	}

	public String hash() {
		return this.hash;
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	public PaintAtlas.Page page() {
		return this.page;
	}

	int cell() {
		return this.cell;
	}

	public int x() {
		return this.x;
	}

	public int y() {
		return this.y;
	}

	public float u0() {
		return (this.x + INSET) / PaintAtlas.PAGE_SIZE;
	}

	public float v0() {
		return (this.y + INSET) / PaintAtlas.PAGE_SIZE;
	}

	public float u1() {
		return (this.x + this.width - INSET) / PaintAtlas.PAGE_SIZE;
	}

	public float v1() {
		return (this.y + this.height - INSET) / PaintAtlas.PAGE_SIZE;
	}

	public float textWidth() {
		return PaintCanvas.textWidth(this.width);
	}

	public float textTop() {
		return PaintCanvas.TEXT_TOP;
	}

	public float textBottom() {
		return PaintCanvas.textBottom(this.height);
	}

	/**
	 * False once the painting has been evicted.
	 *
	 * <p>An entity can outlive its painting, and drawing an evicted one would show whatever moved
	 * into its cell, so holders re-resolve when they see this go false.
	 */
	public boolean isValid() {
		return this.valid;
	}

	void invalidate() {
		this.valid = false;
	}
}
