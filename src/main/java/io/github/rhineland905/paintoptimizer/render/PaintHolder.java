package io.github.rhineland905.paintoptimizer.render;

import net.minecraft.network.chat.Component;

/**
 * Per-entity memory of what we decided about a text display, mixed into
 * {@code Display$TextDisplay}.
 *
 * <p>Hanging it off the entity rather than a map keyed by component keeps the lifetime honest: the
 * decision dies with the entity, and it is invalidated by comparing the text component by identity,
 * which is free and flips exactly when the server sends new text.
 */
public interface PaintHolder {
	PaintBinding paintoptimizer$binding();

	/** The text component the current binding was made for; compared by identity. */
	Component paintoptimizer$bindingSource();

	void paintoptimizer$setBinding(Component source, PaintBinding binding);

	/** How often this display's text has been churning lately. Created on first use. */
	PaintVolatility paintoptimizer$volatility();

	/**
	 * Drops vanilla's cached line layout.
	 *
	 * <p>Only for handing a display back after we suppressed its layout; calling it needlessly makes
	 * vanilla re-split the whole component.
	 */
	void paintoptimizer$releaseLayout();
}
