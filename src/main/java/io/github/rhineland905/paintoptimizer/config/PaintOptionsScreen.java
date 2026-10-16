package io.github.rhineland905.paintoptimizer.config;

import io.github.rhineland905.paintoptimizer.render.ClientHooks;
import io.github.rhineland905.paintoptimizer.render.PaintCache;
import io.github.rhineland905.paintoptimizer.render.PaintGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The settings, built out of vanilla's own option widgets so they look like every other options screen. */
public class PaintOptionsScreen extends OptionsSubScreen {
	private static final int BUTTON_WIDTH = 150;
	private static final int STATUS_COLOUR = 0xFFA0A0A0;
	private static final long MEGABYTE = 1024L * 1024L;

	public PaintOptionsScreen(Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("paintoptimizer.options.title"));
	}

	@Override
	protected void addOptions() {
		PaintConfig config = PaintConfig.get();
		this.list.addBig(OptionInstance.createBoolean(
			"paintoptimizer.options.enabled",
			OptionInstance.cachedConstantTooltip(Component.translatable("paintoptimizer.options.enabled.tooltip")),
			config.enabled,
			ClientHooks::setEnabled
		));
		this.list.addBig(new OptionInstance<>(
			"paintoptimizer.options.cache_size",
			OptionInstance.cachedConstantTooltip(Component.translatable("paintoptimizer.options.cache_size.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, Component.translatable("paintoptimizer.options.megabytes", value)),
			new OptionInstance.IntRange(PaintConfig.MIN_CACHE_MB / PaintConfig.CACHE_STEP_MB, PaintConfig.MAX_CACHE_MB / PaintConfig.CACHE_STEP_MB)
				.xmap(steps -> steps * PaintConfig.CACHE_STEP_MB, megabytes -> megabytes / PaintConfig.CACHE_STEP_MB, true),
			config.cacheSizeMb,
			value -> {
				config.cacheSizeMb = value;
				PaintCache.applyLimit();
			}
		));
	}

	@Override
	protected void addFooter() {
		LinearLayout row = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		row.addChild(Button.builder(Component.translatable("paintoptimizer.options.clear"), button -> PaintCache.clear()).width(BUTTON_WIDTH).build());
		row.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(BUTTON_WIDTH).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
		super.render(graphics, mouseX, mouseY, a);
		int y = this.height - this.layout.getFooterHeight() - this.font.lineHeight - HeaderAndFooterLayout.MAGIC_PADDING;
		graphics.drawCenteredString(this.font, status(), this.width / 2, y, STATUS_COLOUR);
	}

	@Override
	public void removed() {
		super.removed();
		PaintConfig.save();
	}

	private static Component status() {
		if (!PaintConfig.get().enabled) {
			return Component.translatable("paintoptimizer.options.status.off");
		}

		if (!PaintGeometry.isUsable()) {
			return Component.translatable("paintoptimizer.options.status.mismatch");
		}

		return Component.translatable("paintoptimizer.options.status",
			PaintCache.usedBytes() / MEGABYTE, PaintCache.limitBytes() / MEGABYTE, PaintCache.paintingCount());
	}
}
