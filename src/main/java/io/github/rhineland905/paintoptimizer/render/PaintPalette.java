package io.github.rhineland905.paintoptimizer.render;

/**
 * MC Paint's fixed colour palette.
 *
 * <p>A pixel is five bits per channel in steps of 8/255, giving 32768 colours, and the datapack
 * addresses them by index. Isolated pixels are encoded as a single codepoint rather than a colour
 * change, because one glyph is cheaper than a whole text component.
 *
 * <p>Those codepoints are almost, but not quite, {@code 0x4E00 + index}. The run starts in the CJK
 * ideographs and jumps once, from {@code U+A47F} straight to {@code U+AC20}, to skip the scripts in
 * between and land in Hangul — an off-by-1952 that would silently recolour every index from 22144
 * up, which is most of the bright half of the palette. Both the datapack's lookup table and the
 * resource pack's 32 font pages agree on the jump, in every MC Paint version checked.
 */
public final class PaintPalette {
	public static final int SIZE = 32768;

	/** Where the codepoint run starts: CJK Unified Ideographs. */
	private static final int FIRST_BASE = 0x4E00;

	/** Indices covered before the jump; index 22143 is {@code U+A47F}, the last of this block. */
	private static final int FIRST_LENGTH = 22144;

	/** Where it resumes: Hangul syllables, 32 codepoints into the block. */
	private static final int SECOND_BASE = 0xAC20;

	private PaintPalette() {
	}

	/** The codepoint MC Paint encodes palette entry {@code index} as. */
	public static int codepoint(int index) {
		return index < FIRST_LENGTH ? FIRST_BASE + index : SECOND_BASE + index - FIRST_LENGTH;
	}

	/** The palette index {@code codepoint} stands for, or -1 if it does not stand for one. */
	public static int index(int codepoint) {
		if (codepoint >= FIRST_BASE && codepoint < FIRST_BASE + FIRST_LENGTH) {
			return codepoint - FIRST_BASE;
		}

		if (codepoint >= SECOND_BASE && codepoint < SECOND_BASE + SIZE - FIRST_LENGTH) {
			return FIRST_LENGTH + codepoint - SECOND_BASE;
		}

		return -1;
	}

	/** Palette entry {@code index} as 0xRRGGBB. */
	public static int rgb(int index) {
		int r = (index >> 10 & 31) * 8;
		int g = (index >> 5 & 31) * 8;
		int b = (index & 31) * 8;

		return r << 16 | g << 8 | b;
	}
}
