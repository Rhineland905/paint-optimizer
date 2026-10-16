package io.github.rhineland905.paintoptimizer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.joml.Matrix4f;

/**
 * Draws a baked painting in place of the text it was decoded from.
 *
 * <p>The point of the whole mod is here: one quad instead of one glyph per run of pixels. The
 * transform below is vanilla's, line for line, because the replacement has to land in exactly the
 * same place as the text — a painting that shifts by a pixel is worse than a slow one.
 *
 * <p>It also uses the same render type the font would have (the text pipeline, with its depth bias),
 * so the painting keeps winning the depth fight against the item display frame behind it.
 */
public final class PaintRenderer {
	/** The scale vanilla shrinks font space by before drawing display text into the world. */
	private static final float TEXT_SCALE = 0.025F;

	private PaintRenderer() {
	}

	public static void submit(
			PaintBinding binding, TextDisplayEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light, float partialTick
	) {
		BakedPaint paint = binding.paint();
		Display.TextDisplay.TextRenderState text = state.textRenderState;
		byte flags = text.flags();
		boolean seeThrough = (flags & Display.TextDisplay.FLAG_SEE_THROUGH) != 0;
		boolean defaultBackground = (flags & Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND) != 0;

		int background;

		if (defaultBackground) {
			float opacity = Minecraft.getInstance().gameRenderer.gameRenderState().optionsRenderState.getBackgroundOpacity(0.25F);
			background = (int)(opacity * 255.0F) << 24;
		} else {
			background = text.backgroundColor().get(partialTick);
		}

		Matrix4f matrix = poseStack.last().pose();
		matrix.rotate((float)Math.PI, 0.0F, 1.0F, 0.0F);
		matrix.scale(-TEXT_SCALE, -TEXT_SCALE, -TEXT_SCALE);

		float width = paint.textWidth();
		float top = paint.textTop();
		float bottom = paint.textBottom();
		matrix.translate(1.0F - width / 2.0F, -bottom, 0.0F);

		if (background != 0) {
			// Same call vanilla makes for display text, so the backdrop matches it exactly.
			collector.submitTextBackground(poseStack, -1.0F, -1.0F, width, bottom, background,
				seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.POLYGON_OFFSET, light);
		}

		// The glyphs carried their shading in the atlas and their colour in the vertex; our texture
		// carries the colour, so shading and opacity go in the vertex instead. The shade values are
		// the exact greys of the font and the shader still does one float multiply, so a run of
		// pixels comes out bit-identical; an isolated pixel, whose glyph the pack pre-rounded, can
		// differ by one 255th.
		int shade = binding.font().shade();
		int alpha = text.textOpacity().get(partialTick) & 0xFF;
		int tint = alpha << 24 | shade << 16 | shade << 8 | shade;

		float u0 = paint.u0();
		float v0 = paint.v0();
		float u1 = paint.u1();
		float v1 = paint.v1();

		// Every painting on an atlas page shares this render type, so the game draws them in one batch.
		collector.order(background != 0 ? 1 : 0).submitCustomGeometry(poseStack, GpuPages.renderType(paint, seeThrough), (pose, vertices) -> {
			// Wound like a glyph quad — top left, bottom left, bottom right, top right — so back-face
			// culling treats the replacement the same way it treated the text.
			vertices.addVertex(pose, 0.0F, top, 0.0F).setColor(tint).setUv(u0, v0).setLight(light);
			vertices.addVertex(pose, 0.0F, bottom, 0.0F).setColor(tint).setUv(u0, v1).setLight(light);
			vertices.addVertex(pose, width, bottom, 0.0F).setColor(tint).setUv(u1, v1).setLight(light);
			vertices.addVertex(pose, width, top, 0.0F).setColor(tint).setUv(u1, v0).setLight(light);
		});
	}
}
