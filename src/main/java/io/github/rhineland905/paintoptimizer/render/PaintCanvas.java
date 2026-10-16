package io.github.rhineland905.paintoptimizer.render;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * One decoded MC Paint canvas: straight ARGB pixels, row major, origin top left.
 *
 * <p>Colours are the unshaded palette values, because {@link PaintFont} shading is applied at draw
 * time. A pixel of 0 is fully transparent, which is what MC Paint's spacing glyphs mean.
 */
public record PaintCanvas(int width, int height, int[] pixels) {
	/** Font advance units one canvas pixel occupies, both horizontally and vertically. */
	public static final int PIXEL_SIZE = 10;
	/** Top edge of the equivalent text in font space; the glyphs start one unit above the baseline. */
	public static final float TEXT_TOP = -1.0F;

	public int pixelCount() {
		return this.width * this.height;
	}

	/** Width of the equivalent text, in the font space {@code TextDisplayRenderer} lays out in. */
	public float textWidth() {
		return textWidth(this.width);
	}

	public static float textWidth(int width) {
		return width * PIXEL_SIZE;
	}

	/**
	 * Bottom edge of the equivalent text in font space.
	 *
	 * <p>Vanilla stacks lines every {@code 9 + 1} units and subtracts one for the trailing gap, and
	 * the glyphs themselves run from {@code -1} to {@code lines * 10 - 1}. Matching that exactly is
	 * what keeps a replaced painting in the same place as the one it replaced.
	 */
	public float textBottom() {
		return textBottom(this.height);
	}

	public static float textBottom(int height) {
		return height * PIXEL_SIZE - 1;
	}

	public float textTop() {
		return TEXT_TOP;
	}

	/**
	 * Content hash, the cache key: two entities showing the same picture share one cell.
	 *
	 * <p>Fed to the digest a kilobyte at a time, so hashing a big canvas does not allocate a copy of it.
	 */
	public String hash() {
		MessageDigest digest;

		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", e);
		}

		digest.update(("mcpaint:" + this.width + "x" + this.height + ":").getBytes(StandardCharsets.US_ASCII));
		byte[] chunk = new byte[1024];

		for (int i = 0; i < this.pixels.length; ) {
			int n = Math.min(chunk.length / 4, this.pixels.length - i);

			for (int k = 0; k < n; k++) {
				int argb = this.pixels[i + k];
				chunk[k * 4] = (byte)(argb >>> 24);
				chunk[k * 4 + 1] = (byte)(argb >>> 16);
				chunk[k * 4 + 2] = (byte)(argb >>> 8);
				chunk[k * 4 + 3] = (byte)argb;
			}

			digest.update(chunk, 0, n * 4);
			i += n;
		}

		byte[] full = digest.digest();
		byte[] truncated = new byte[16];
		System.arraycopy(full, 0, truncated, 0, truncated.length);

		return HexFormat.of().formatHex(truncated);
	}
}
