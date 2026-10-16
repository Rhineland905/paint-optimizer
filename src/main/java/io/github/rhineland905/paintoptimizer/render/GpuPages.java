package io.github.rhineland905.paintoptimizer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import io.github.rhineland905.paintoptimizer.PaintOptimizer;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The atlas pages as GPU textures, registered with the texture manager so the text render types can
 * find them.
 *
 * <p>Pages use the same render types the font would have (the text pipeline, with its depth bias),
 * so a painting keeps winning the depth fight against the item frame behind it. Vanilla memoises a
 * render type per texture id and never forgets one; page ids are reused, so that stays bounded by
 * the most pages there have ever been at once.
 */
final class GpuPages implements PaintAtlas.Backend {
	/** Staging buffer for uploads, reused; both GPU backends copy out of it before returning. */
	private @Nullable ByteBuffer staging;

	private record Handle(Identifier id, PageTexture texture, RenderType polygonOffset, RenderType seeThrough) {
	}

	@Override
	public Object createPage(int index) {
		Identifier id = Identifier.fromNamespaceAndPath(PaintOptimizer.MOD_ID, "atlas/" + index);
		PageTexture texture = new PageTexture(id);
		Minecraft.getInstance().getTextureManager().register(id, texture);
		return new Handle(id, texture, RenderTypes.textPolygonOffset(id), RenderTypes.textSeeThrough(id));
	}

	@Override
	public void upload(PaintAtlas.Page page, int x, int y, PaintCanvas canvas) {
		int bytes = canvas.pixelCount() * 4;
		if (this.staging == null || this.staging.capacity() < bytes) {
			this.staging = ByteBuffer.allocateDirect(Math.max(bytes, 64 * 64 * 4));
		}

		// RGBA8 in memory order; the canvas is ARGB ints.
		ByteBuffer buffer = this.staging.clear();
		for (int argb : canvas.pixels()) {
			buffer.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
		}

		buffer.flip();
		GpuTexture texture = handle(page).texture.getTexture();
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, buffer, 0, 0, x, y, canvas.width(), canvas.height());
	}

	@Override
	public void releasePage(PaintAtlas.Page page) {
		// release() unregisters and closes the texture; closing it here as well would be a double free.
		Minecraft.getInstance().getTextureManager().release(handle(page).id);
	}

	static RenderType renderType(BakedPaint paint, boolean seeThrough) {
		Handle handle = handle(paint.page());
		return seeThrough ? handle.seeThrough : handle.polygonOffset;
	}

	private static Handle handle(PaintAtlas.Page page) {
		return (Handle) page.handle();
	}

	/**
	 * One page: a GPU texture and nothing else. Unlike {@code DynamicTexture} it keeps no copy of the
	 * pixels in memory, which would double what the cache costs.
	 */
	private static final class PageTexture extends AbstractTexture {
		PageTexture(Identifier id) {
			GpuDevice device = RenderSystem.getDevice();
			this.texture = device.createTexture(() -> "Paint Optimizer " + id.getPath(), GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM, PaintAtlas.PAGE_SIZE, PaintAtlas.PAGE_SIZE, 1, 1);
			this.textureView = device.createTextureView(this.texture);
			// Nearest, like the font atlas: a stretched canvas keeps the blocky pixels of the glyphs it replaces.
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
	}
}
