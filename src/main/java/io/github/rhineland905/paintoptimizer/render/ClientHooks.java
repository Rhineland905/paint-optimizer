package io.github.rhineland905.paintoptimizer.render;

import io.github.rhineland905.paintoptimizer.config.PaintConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.jspecify.annotations.Nullable;

/**
 * The renderer's client lifecycle, driven entirely from the frame hook.
 *
 * <p>Joining and leaving are read off the connection object rather than subscribed to: it is one
 * reference compare per frame and it puts every cache change on the render thread, where freeing a
 * texture is legal. Dimension changes keep the same connection, so a trip to the Nether does not
 * throw away the paintings of the overworld.
 */
public final class ClientHooks {
	private static @Nullable ClientPacketListener connection;
	private static int fontGeneration;

	private ClientHooks() {
	}

	public static void onFrameStart() {
		PaintCache.onFrameStart();

		ClientPacketListener current = Minecraft.getInstance().getConnection();
		if (current != connection) {
			if (connection != null) {
				onLeave();
			}

			connection = current;
		}
	}

	/** Which fonts a text layout was measured in; see {@link #onFontsChanged()}. */
	public static int fontGeneration() {
		return fontGeneration;
	}

	/**
	 * The fonts changed: a resource pack was applied, or a font option was switched.
	 *
	 * <p>Vanilla keeps a text display's layout until its text changes, so a layout measured in the old
	 * fonts outlives them. Joining a server with MC Paint's pack does exactly that: the world is on
	 * screen for a moment before the pack is applied, the paintings are laid out in the default font,
	 * and once the pack arrives vanilla keeps drawing its rows at the old widths, shifted sideways.
	 * So every layout and every decision made in the old fonts is retired here, and the geometry
	 * check is earned again in the new ones.
	 */
	public static void onFontsChanged() {
		fontGeneration++;
		PaintGeometry.reset();
		PaintCache.invalidateBindings();
	}

	/** Applies the on/off switch. Off frees every painting, so the mod holds no memory at all. */
	public static void setEnabled(boolean value) {
		PaintConfig config = PaintConfig.get();
		if (config.enabled == value) {
			return;
		}

		config.enabled = value;
		if (value) {
			PaintCache.invalidateBindings();
		} else {
			PaintCache.clear();
		}
	}

	private static void onLeave() {
		// Paintings belong to a world: a canvas on the next server is a different canvas even if the
		// pixels happen to match. That server also means a different resource pack, so the geometry
		// check has to be earned again rather than inherited.
		PaintCache.clear();
		PaintGeometry.reset();
	}
}
