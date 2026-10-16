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

	/** A resource pack swap can change the advances the geometry check was verified against. */
	public static void onResourceReload() {
		PaintGeometry.reset();
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
