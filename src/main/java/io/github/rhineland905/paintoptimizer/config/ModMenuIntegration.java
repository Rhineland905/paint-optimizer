package io.github.rhineland905.paintoptimizer.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * The "Config" button in Mod Menu's mod list.
 *
 * <p>Mod Menu only loads this entry point when it is installed itself, so the mod has no runtime
 * dependency on it.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return PaintOptionsScreen::new;
	}
}
