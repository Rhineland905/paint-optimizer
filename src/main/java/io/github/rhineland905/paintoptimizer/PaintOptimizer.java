package io.github.rhineland905.paintoptimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paint Optimizer: draws MC Paint paintings as one cached texture each instead of thousands of
 * glyphs, inside a memory limit the player sets.
 *
 * <p>There is no initialiser: everything starts from the mixins, and the config loads on first use.
 * The renderer is ported to Minecraft 26.3 from MC Paint Fix by Krypton Dev
 * (https://github.com/krypt0nmm/mcpaint-fix, MIT).
 */
public final class PaintOptimizer {
	public static final String MOD_ID = "paintoptimizer";
	public static final Logger LOGGER = LoggerFactory.getLogger("Paint Optimizer");

	private PaintOptimizer() {
	}
}
