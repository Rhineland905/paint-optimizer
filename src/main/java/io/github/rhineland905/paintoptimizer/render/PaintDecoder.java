package io.github.rhineland905.paintoptimizer.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Turns an MC Paint text component back into the picture it draws.
 *
 * <p>MC Paint run-length encodes each row of a canvas into characters of its own font, which is why
 * a painting costs thousands of glyphs a frame to render. The encoding, read out of the datapack's
 * {@code canvas/internal/compile_row} functions and the font definitions in its resource pack, is:
 *
 * <ul>
 *   <li>{@code a}-{@code p} — a transparent run of 1 to 16 pixels; longer gaps concatenate, so
 *       {@code pa} is 17. These carry no colour.
 *   <li>{@code A}-{@code P} — an opaque run of 1 to 16 pixels in the segment's colour, again
 *       concatenated for longer runs.
 *   <li>{@code .} — a space glyph of advance -1 that cancels the one unit Minecraft inserts between
 *       glyphs, so that one pixel is exactly {@link PaintCanvas#PIXEL_SIZE} units wide. Skipped.
 *   <li>a CJK or Hangul codepoint — a single pixel whose colour is baked into the glyph itself, see
 *       {@link PaintPalette}. Cheaper than a colour change for an isolated pixel, and the reason
 *       these paintings look like walls of Chinese when the resource pack is missing.
 *   <li>{@code \n} — end of row.
 * </ul>
 *
 * <p>Decoding the component beats rendering it and reading the framebuffer back: it is exact, needs
 * no GPU round trip, works before the painting is ever on screen, and does not depend on the
 * resource pack being installed.
 */
public final class PaintDecoder {
	/** Longest run a single solid or spacing glyph can encode. */
	private static final int MAX_RUN = 16;

	private static final char SOLID_FIRST = 'A';
	private static final char SOLID_LAST = 'P';
	private static final char SPACING_FIRST = 'a';
	private static final char SPACING_LAST = 'p';
	private static final char KERNING = '.';
	private static final char ROW_BREAK = '\n';

	private PaintDecoder() {
	}

	/**
	 * Decodes {@code text}, or returns {@code null} if it is not something we can reproduce exactly.
	 *
	 * <p>Returning null is always safe: the caller falls back to vanilla rendering, which is slow but
	 * correct. Guessing would put a wrong picture on a wall, so anything unexpected fails the whole
	 * decode rather than being patched over.
	 */
	public static PaintCanvas decode(Component text, int maxPixels) {
		Decoder decoder = new Decoder(maxPixels);

		if (text.visit(decoder, Style.EMPTY).isPresent()) {
			return null;
		}

		return decoder.finish();
	}

	private static final class Decoder implements FormattedText.StyledContentConsumer<Boolean> {
		/** Returned from {@link #accept} to abort the visit; the value itself is never read. */
		private static final Optional<Boolean> ABORT = Optional.of(Boolean.FALSE);

		private final List<int[]> rows = new ArrayList<>();
		private final int maxPixels;

		private int[] row = new int[256];
		private int rowLength;
		private int total;

		Decoder(int maxPixels) {
			this.maxPixels = maxPixels;
		}

		@Override
		public Optional<Boolean> accept(Style style, String string) {
			TextColor colour = style.getColor();
			int runArgb = colour == null ? 0 : 0xFF000000 | colour.getValue() & 0xFFFFFF;

			for (int i = 0; i < string.length(); ) {
				int codepoint = string.codePointAt(i);
				i += Character.charCount(codepoint);

				if (codepoint == KERNING) {
					continue;
				}

				if (codepoint == ROW_BREAK) {
					this.endRow();
					continue;
				}

				if (codepoint >= SPACING_FIRST && codepoint <= SPACING_LAST) {
					if (!this.append(0, codepoint - SPACING_FIRST + 1)) {
						return ABORT;
					}
				} else if (codepoint >= SOLID_FIRST && codepoint <= SOLID_LAST) {
					// A solid run with no colour on it would be a segment we have misread, and
					// guessing white here would silently corrupt the picture.
					if (colour == null || !this.append(runArgb, codepoint - SOLID_FIRST + 1)) {
						return ABORT;
					}
				} else {
					int index = PaintPalette.index(codepoint);

					if (index < 0 || !this.append(0xFF000000 | PaintPalette.rgb(index), 1)) {
						return ABORT;
					}
				}
			}

			return Optional.empty();
		}

		private boolean append(int argb, int count) {
			this.total += count;

			if (count > MAX_RUN || this.total > this.maxPixels) {
				return false;
			}

			if (this.rowLength + count > this.row.length) {
				this.row = Arrays.copyOf(this.row, Math.max(this.rowLength + count, this.row.length * 2));
			}

			Arrays.fill(this.row, this.rowLength, this.rowLength + count, argb);
			this.rowLength += count;

			return true;
		}

		private void endRow() {
			this.rows.add(Arrays.copyOf(this.row, this.rowLength));
			this.rowLength = 0;
		}

		PaintCanvas finish() {
			this.endRow();

			int height = this.rows.size();

			if (height == 0) {
				return null;
			}

			int width = this.rows.get(0).length;

			if (width == 0) {
				return null;
			}

			// Every row of a canvas is the full width, including its trailing transparent run. A
			// ragged result means the encoding is not what we think it is, so refuse it.
			for (int[] row : this.rows) {
				if (row.length != width) {
					return null;
				}
			}

			int[] pixels = new int[width * height];

			for (int y = 0; y < height; y++) {
				System.arraycopy(this.rows.get(y), 0, pixels, y * width, width);
			}

			return new PaintCanvas(width, height, pixels);
		}
	}
}
