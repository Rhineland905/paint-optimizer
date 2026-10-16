package io.github.rhineland905.paintoptimizer.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Random;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class PaintDecoderTest {
	private static final int LIMIT = 1 << 20;

	@Test
	@DisplayName("a canvas survives a round trip through MC Paint's encoding")
	void roundTripsRandomCanvases() {
		Random random = new Random(20260810L);

		for (int trial = 0; trial < 200; trial++) {
			int width = 1 + random.nextInt(64);
			int height = 1 + random.nextInt(64);
			int[][] rows = randomCanvas(random, width, height);

			PaintCanvas decoded = PaintDecoder.decode(McPaintEncoder.encode(PaintFont.FULLBRIGHT, rows), LIMIT);

			assertNotNull(decoded, "trial " + trial + " (" + width + "x" + height + ") failed to decode");
			assertEquals(width, decoded.width());
			assertEquals(height, decoded.height());

			for (int y = 0; y < height; y++) {
				assertArrayEquals(rows[y], row(decoded, y), "trial " + trial + ", row " + y);
			}
		}
	}

	@Test
	@DisplayName("runs longer than one glyph, isolated pixels and gaps inside a run all decode")
	void handlesTheAwkwardRuns() {
		// Palette colours: MC Paint quantises to five bits a channel, and a lone pixel is encoded as a
		// palette index, so an off-grid colour is not something the datapack can produce.
		int red = 0xFF000000 | PaintPalette.rgb(31 << 10);
		int blue = 0xFF000000 | PaintPalette.rgb(31);

		// Row layout, left to right: a 17 pixel run (two glyphs), a gap, a lone pixel of the same
		// colour as the run before it, a 64 pixel run, a lone pixel of a different colour.
		int[] row = new int[17 + 3 + 1 + 64 + 1];
		int x = 0;

		for (int i = 0; i < 17; i++) {
			row[x++] = red;
		}

		x += 3;
		row[x++] = red;

		for (int i = 0; i < 64; i++) {
			row[x++] = blue;
		}

		row[x] = red;

		PaintCanvas decoded = PaintDecoder.decode(McPaintEncoder.encode(PaintFont.Z_FACE, new int[][] {row}), LIMIT);

		assertNotNull(decoded);
		assertEquals(row.length, decoded.width());
		assertEquals(1, decoded.height());
		assertArrayEquals(row, decoded.pixels());
	}

	@Test
	@DisplayName("a fully transparent canvas is still a canvas")
	void handlesEmptyRows() {
		PaintCanvas decoded = PaintDecoder.decode(McPaintEncoder.encode(PaintFont.FULLBRIGHT, new int[4][7]), LIMIT);

		assertNotNull(decoded);
		assertEquals(7, decoded.width());
		assertEquals(4, decoded.height());

		for (int pixel : decoded.pixels()) {
			assertEquals(0, pixel);
		}
	}

	@Test
	@DisplayName("the replacement occupies the same font space the text did")
	void reportsVanillaGeometry() {
		PaintCanvas canvas = new PaintCanvas(13, 7, new int[13 * 7]);

		// Vanilla lays out one line every 9 + 1 units and one MC Paint pixel is 10 units wide, so a
		// 13x7 canvas is 130 units across and runs from -1 to 69 vertically.
		assertEquals(130.0F, canvas.textWidth());
		assertEquals(-1.0F, canvas.textTop());
		assertEquals(69.0F, canvas.textBottom());
	}

	@Test
	@DisplayName("anything that is not the encoding we know is refused rather than guessed at")
	void refusesForeignText() {
		assertNull(PaintDecoder.decode(Component.literal("hello world"), LIMIT));
		assertNull(PaintDecoder.decode(Component.literal(""), LIMIT));

		// Solid runs carry their colour on the segment; one without a colour is a segment we have
		// misread, and painting it white would be worse than not painting it.
		assertNull(PaintDecoder.decode(Component.literal("D."), LIMIT));

		// Ragged rows mean the encoding is not what we think it is.
		assertNull(PaintDecoder.decode(coloured("D.\nB."), LIMIT));
	}

	@Test
	@DisplayName("a canvas larger than the limit is refused instead of allocated")
	void respectsTheSizeLimit() {
		int[][] rows = randomCanvas(new Random(7L), 64, 64);

		assertNull(PaintDecoder.decode(McPaintEncoder.encode(PaintFont.FULLBRIGHT, rows), 1024));
	}

	@Test
	@DisplayName("the four canvas fonts are recognised and nothing else is")
	void recognisesCanvasFonts() {
		assertEquals(PaintFont.FULLBRIGHT, fontOf("mcpaint", "ink/fullbright"));
		assertEquals(PaintFont.Z_FACE, fontOf("mcpaint", "ink/z_face"));
		assertEquals(PaintFont.X_FACE, fontOf("mcpaint", "ink/x_face"));
		assertEquals(PaintFont.BOTTOM_FACE, fontOf("mcpaint", "ink/bottom_face"));

		assertNull(fontOf("mcpaint", "studio_ui"));
		assertNull(fontOf("mcpaint", "ink/chat"));
		assertNull(fontOf("minecraft", "default"));
		assertNull(PaintFont.of(Style.EMPTY));

		// Vanilla block face shading, which is what the glyphs are pre-multiplied by.
		assertEquals(255, PaintFont.FULLBRIGHT.shade());
		assertEquals(204, PaintFont.Z_FACE.shade());
		assertEquals(153, PaintFont.X_FACE.shade());
		assertEquals(127, PaintFont.BOTTOM_FACE.shade());
	}

	@Test
	@DisplayName("palette codepoints match the datapack's lookup table, jump and all")
	void matchesTheCodepointTable() {
		// Read straight out of MC Paint's lookup.cjk — identical in 1.2 and 1.7.0 — and confirmed
		// against the 32 font pages in its resource pack. The run is NOT 0x4E00 + index throughout:
		// it jumps 1952 codepoints at index 22144, and a decoder that misses that recolours most of
		// the bright half of the palette. Round-tripping through our own encoder cannot catch this,
		// which is why these are literals.
		assertEquals(0x4E00, PaintPalette.codepoint(0));
		assertEquals(0x4E01, PaintPalette.codepoint(1));
		assertEquals(0xA47F, PaintPalette.codepoint(22143));
		assertEquals(0xAC20, PaintPalette.codepoint(22144));
		assertEquals(0xD59F, PaintPalette.codepoint(PaintPalette.SIZE - 1));

		for (int index = 0; index < PaintPalette.SIZE; index++) {
			assertEquals(index, PaintPalette.index(PaintPalette.codepoint(index)));
		}

		// Everything outside the two blocks has to be refused, or a stray character would paint.
		assertEquals(-1, PaintPalette.index(0x4DFF));
		assertEquals(-1, PaintPalette.index(0xA480));
		assertEquals(-1, PaintPalette.index(0xAC1F));
		assertEquals(-1, PaintPalette.index(0xD5A0));
		assertEquals(-1, PaintPalette.index('A'));
	}

	@Test
	@DisplayName("a single pixel past the codepoint jump decodes to the right colour")
	void decodesAcrossTheCodepointJump() {
		// U+AC20 is palette index 22144, which is r=21 g=20 b=0 in five-bit channels.
		PaintCanvas decoded = PaintDecoder.decode(Component.literal("\uAC20."), LIMIT);

		assertNotNull(decoded);
		assertEquals(1, decoded.width());
		assertEquals(1, decoded.height());
		assertEquals(0xFFA8A000, decoded.pixels()[0]);

		// The gap between the two blocks is not a colour.
		assertNull(PaintDecoder.decode(Component.literal("\uA480."), LIMIT));
	}

	@Test
	@DisplayName("the palette matches the datapack's lookup table")
	void matchesThePalette() {
		assertEquals(0x000000, PaintPalette.rgb(0));
		assertEquals(0x000008, PaintPalette.rgb(1));
		assertEquals(0x0000F8, PaintPalette.rgb(31));
		assertEquals(0x000800, PaintPalette.rgb(32));
		assertEquals(0x00F8F8, PaintPalette.rgb(1023));
		assertEquals(0xF8F8F8, PaintPalette.rgb(PaintPalette.SIZE - 1));

		for (int index = 0; index < PaintPalette.SIZE; index++) {
			assertEquals(index, McPaintEncoder.paletteIndex(PaintPalette.rgb(index)));
		}
	}

	@Test
	@DisplayName("the hash follows the pixels, not the object")
	void hashesContent() {
		int[][] rows = randomCanvas(new Random(11L), 16, 16);
		Component text = McPaintEncoder.encode(PaintFont.FULLBRIGHT, rows);

		PaintCanvas first = PaintDecoder.decode(text, LIMIT);
		PaintCanvas second = PaintDecoder.decode(McPaintEncoder.encode(PaintFont.X_FACE, rows), LIMIT);

		assertNotNull(first);
		assertNotNull(second);
		// Shading is a property of the entity, so the same picture on two walls is one texture.
		assertEquals(first.hash(), second.hash());

		rows[0][0] = rows[0][0] == 0 ? 0xFF080808 : 0;

		assertNotEquals(first.hash(), PaintDecoder.decode(McPaintEncoder.encode(PaintFont.FULLBRIGHT, rows), LIMIT).hash());
	}

	private static PaintFont fontOf(String namespace, String path) {
		return PaintFont.of(Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(namespace, path))));
	}

	private static Component coloured(String text) {
		return Component.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF0000)));
	}

	private static int[] row(PaintCanvas canvas, int y) {
		int[] out = new int[canvas.width()];
		System.arraycopy(canvas.pixels(), y * canvas.width(), out, 0, canvas.width());

		return out;
	}

	/** A canvas biased towards runs and gaps, which is what real pictures look like once quantised. */
	private static int[][] randomCanvas(Random random, int width, int height) {
		int[][] rows = new int[height][width];

		for (int y = 0; y < height; y++) {
			int x = 0;

			while (x < width) {
				int run = Math.min(width - x, 1 + random.nextInt(random.nextInt(4) == 0 ? 40 : 3));
				int colour = random.nextInt(4) == 0 ? 0 : 0xFF000000 | PaintPalette.rgb(random.nextInt(PaintPalette.SIZE));

				for (int i = 0; i < run; i++) {
					rows[y][x++] = colour;
				}
			}
		}

		return rows;
	}
}
