package io.github.rhineland905.paintoptimizer.render;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * MC Paint's encoder, reimplemented from the datapack's {@code canvas/internal/compile_row}
 * functions.
 *
 * <p>Test-only. It exists so the decoder is checked against the thing it has to invert rather than
 * against a component we wrote to suit it — the interesting bugs live in the seams of the state
 * machine (a lone pixel inside a run of its own colour, a gap that does not break a coloured
 * segment, a run longer than one glyph can hold), and those only show up if the input is generated
 * the way the datapack generates it.
 */
final class McPaintEncoder {
	private static final int NONE = 0;
	private static final int SPACING = 1;
	private static final int COLOURED = 2;
	private static final int CJK = 3;

	private final MutableComponent root;
	private final StringBuilder queue = new StringBuilder();

	private int state = NONE;
	private int queueColour;

	private McPaintEncoder(PaintFont font) {
		this.root = Component.literal("").setStyle(Style.EMPTY.withFont(new FontDescription.Resource(font.fontId())));
	}

	/** Encodes {@code rows} of ARGB pixels, where 0 means transparent, exactly as the datapack would. */
	static Component encode(PaintFont font, int[][] rows) {
		McPaintEncoder encoder = new McPaintEncoder(font);

		for (int y = 0; y < rows.length; y++) {
			int[] row = rows[y];
			encoder.state = NONE;

			for (int x = 0; x < row.length; ) {
				int colour = row[x];
				int length = 1;

				while (x + length < row.length && row[x + length] == colour) {
					length++;
				}

				x += length;

				if (colour == 0) {
					encoder.addSpacing(length);
				} else if (length == 1) {
					encoder.addSingle(colour);
				} else {
					encoder.addSegment(colour, length);
				}
			}

			if (y < rows.length - 1) {
				encoder.queue.append('\n');
			}

			encoder.flush();
		}

		return encoder.root;
	}

	private void addSpacing(int length) {
		this.queue.append(spacingChars(length));

		if (this.state == NONE) {
			this.state = SPACING;
		}
	}

	private void addSingle(int colour) {
		if (this.state == COLOURED && this.queueColour != colour) {
			this.flush();
		}

		if (this.state == COLOURED) {
			this.queue.append("A.");
		} else {
			this.queue.append(cjkChars(colour));
			this.state = CJK;
		}
	}

	private void addSegment(int colour, int length) {
		if (this.state == CJK || this.state == COLOURED && this.queueColour != colour) {
			this.flush();
		}

		this.queue.append(solidChars(length));
		this.queueColour = colour;
		this.state = COLOURED;
	}

	private void flush() {
		if (!this.queue.isEmpty()) {
			MutableComponent segment = Component.literal(this.queue.toString());

			if (this.state == COLOURED) {
				segment.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(this.queueColour & 0xFFFFFF)));
			}

			this.root.append(segment);
		}

		this.queue.setLength(0);
		this.state = NONE;
	}

	private static String spacingChars(int length) {
		return repeat(length, 'a', "p", "");
	}

	private static String solidChars(int length) {
		return repeat(length, 'A', "P.", ".");
	}

	private static String repeat(int length, char first, String full, String suffix) {
		StringBuilder builder = new StringBuilder();

		for (int i = 0; i < length / 16; i++) {
			builder.append(full);
		}

		int rest = length % 16;

		if (rest > 0) {
			builder.append((char)(first + rest - 1)).append(suffix);
		}

		return builder.toString();
	}

	private static String cjkChars(int argb) {
		return (char)PaintPalette.codepoint(paletteIndex(argb)) + ".";
	}

	static int paletteIndex(int argb) {
		return (argb >> 16 & 0xFF) / 8 << 10 | (argb >> 8 & 0xFF) / 8 << 5 | (argb & 0xFF) / 8;
	}
}
