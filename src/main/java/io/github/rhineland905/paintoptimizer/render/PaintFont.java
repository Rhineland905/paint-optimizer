package io.github.rhineland905.paintoptimizer.render;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The four fonts MC Paint draws a canvas with.
 *
 * <p>All four hold the same glyphs, pre-multiplied by Minecraft's block face shading, and the
 * datapack picks one per painting so a canvas is lit like the wall it hangs on. The shade is
 * therefore a property of the entity rather than of the picture, so the mod keeps it out of the
 * cached image and applies it as a vertex tint: one cached image then serves every copy of the
 * same painting no matter which way it faces.
 *
 * <p>The greys are the literal values the resource pack draws the solid glyphs in. Tinting with them
 * reproduces a run of pixels bit for bit, because the shader still does the same single multiply.
 * An isolated pixel can land one 255th off: those glyphs come out of the pack already multiplied and
 * rounded down, where we multiply at full precision and round once at the end.
 */
public enum PaintFont {
	FULLBRIGHT("ink/fullbright", 255),
	Z_FACE("ink/z_face", 204),
	X_FACE("ink/x_face", 153),
	BOTTOM_FACE("ink/bottom_face", 127);

	/** Namespace of the resource pack MC Paint ships with. */
	public static final String NAMESPACE = "mcpaint";

	private final String path;
	private final int shade;

	PaintFont(String path, int shade) {
		this.path = path;
		this.shade = shade;
	}

	/**
	 * The canvas font this style renders with, or {@code null} if it is not an MC Paint canvas.
	 *
	 * <p>This is the whole detection heuristic: the font is set on the root of the resolved text
	 * component and no other pack has a reason to claim {@code mcpaint:ink/*}, which makes it a far
	 * tighter signal than looking for the characters MC Paint encodes pixels with.
	 */
	public static PaintFont of(Style style) {
		if (!(style.getFont() instanceof FontDescription.Resource resource)) {
			return null;
		}

		Identifier id = resource.id();

		if (!NAMESPACE.equals(id.getNamespace())) {
			return null;
		}

		for (PaintFont font : values()) {
			if (font.path.equals(id.getPath())) {
				return font;
			}
		}

		return null;
	}

	/** How bright the glyphs of this font are, 0-255; the tint that replaces the baked-in shading. */
	public int shade() {
		return this.shade;
	}

	public Identifier fontId() {
		return Identifier.fromNamespaceAndPath(NAMESPACE, this.path);
	}
}
