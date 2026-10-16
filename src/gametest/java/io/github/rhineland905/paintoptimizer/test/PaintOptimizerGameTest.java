package io.github.rhineland905.paintoptimizer.test;

import io.github.rhineland905.paintoptimizer.config.PaintConfig;
import io.github.rhineland905.paintoptimizer.config.PaintOptionsScreen;
import io.github.rhineland905.paintoptimizer.render.ClientHooks;
import io.github.rhineland905.paintoptimizer.render.McPaintEncoder;
import io.github.rhineland905.paintoptimizer.render.PaintAtlas;
import io.github.rhineland905.paintoptimizer.render.PaintBinding;
import io.github.rhineland905.paintoptimizer.render.PaintCache;
import io.github.rhineland905.paintoptimizer.render.PaintFont;
import io.github.rhineland905.paintoptimizer.render.PaintHolder;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

/**
 * Paint Optimizer inside a real 26.3 client, with MC Paint's resource pack (PAINTOPT_MCPAINT_RP):
 * paintings must look exactly like vanilla's, the cache must never pass its limit, the paintings seen
 * longest ago must be the ones evicted, and the settings must open from Mod Menu.
 * Screenshots go to build/run/clientGameTest/screenshots.
 */
public class PaintOptimizerGameTest implements FabricClientGameTest {
	private static final int GRID = 10;
	private static final int WALL = GRID * GRID;
	private static final int NOISE_ROWS = 4;
	/** Where the two walls stand: far enough apart that the client stops tracking one at the other. */
	private static final int HOME = 0;
	private static final int AWAY = 400;
	private static final int PER_PAGE = (PaintAtlas.PAGE_SIZE / 64) * (PaintAtlas.PAGE_SIZE / 64);

	@Override
	public void runTest(ClientGameTestContext context) {
		installResourcePack(context);
		// Clouds drift and the vignette fades with time; either would make two screenshots differ.
		context.runOnClient(client -> {
			client.options.cloudStatus().set(CloudStatus.OFF);
			client.options.vignette().set(false);
		});

		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			TestServerContext server = sp.getServer();
			server.runCommand("tp @p 0.5 -60 0.5 0 -15");

			// 1. A wall of 100 different paintings: two atlas pages, pictures identical to vanilla's.
			spawnWall(server, HOME, 0);
			awaitReady(context, HOME, WALL);
			check(context.computeOnClient(client -> PaintCache.pageCount()) == 2, "100 paintings should take 2 pages");
			check(context.computeOnClient(client -> PaintCache.usedBytes()) == 2 * PaintAtlas.PAGE_BYTES, "2 pages should be 2 MB");
			Path fast = screenshot(context, server, "po-fast");
			long fastTime = frameTime(context);

			context.runOnClient(client -> ClientHooks.setEnabled(false));
			check(context.computeOnClient(client -> PaintCache.usedBytes()) == 0, "switching off should free the cache");
			context.waitTicks(20);
			Path vanilla = screenshot(context, server, "po-vanilla");
			long vanillaTime = frameTime(context);
			double differs = difference(fast, vanilla);
			log(String.format(Locale.ROOT, "100 paintings vs vanilla: %.3f%% of pixels differ; frame+tick %.2f ms fast, %.2f ms vanilla",
				differs * 100, fastTime / 1e6, vanillaTime / 1e6));
			check(differs < 0.002, "paintings look different from vanilla");

			context.runOnClient(client -> ClientHooks.setEnabled(true));
			awaitReady(context, HOME, WALL);

			// 2. A 1 MB limit with all 100 on screen: one page, the rest drawn the vanilla way, nothing evicted back and forth.
			context.runOnClient(client -> PaintCache.setLimitPages(1));
			check(context.computeOnClient(client -> PaintCache.usedBytes()) == PaintAtlas.PAGE_BYTES, "lowering the limit should free pages at once");
			awaitReady(context, HOME, PER_PAGE);
			context.waitTicks(10);
			long evictions = context.computeOnClient(client -> PaintCache.evictions());
			context.waitTicks(120);
			check(context.computeOnClient(client -> PaintCache.evictions()) == evictions, "paintings on screen keep evicting each other");
			check(context.computeOnClient(client -> PaintCache.pageCount()) == 1, "more pages than the limit");
			check(count(context, HOME, PaintBinding::isReady) == PER_PAGE, "expected exactly one page of paintings baked");
			Path limited = screenshot(context, server, "po-limited");
			double limitedDiffers = difference(limited, vanilla);
			log(String.format(Locale.ROOT, "1 MB limit: %d baked, %d drawn the vanilla way, %.3f%% of pixels differ from vanilla",
				PER_PAGE, WALL - PER_PAGE, limitedDiffers * 100));
			check(limitedDiffers < 0.002, "paintings that did not fit were not drawn");

			// 3. Walking to a new wall evicts the old one: the paintings seen longest ago go first.
			// (MC Paint's displays are never frustum-culled, so "seen" means within entity render distance.)
			List<String> oldWall = context.computeOnClient(client -> wall(client, HOME).stream()
				.map(d -> ((PaintHolder) d).paintoptimizer$binding())
				.filter(b -> b != null && b.isReady())
				.map(b -> b.paint().hash())
				.toList());
			check(oldWall.size() == PER_PAGE, "expected a page of the old wall cached");
			server.runCommand("tp @p " + (AWAY + 0.5) + " -60 0.5 0 -15");
			spawnWall(server, AWAY, 1000);
			awaitReady(context, AWAY, PER_PAGE);
			long kept = context.computeOnClient(client -> oldWall.stream().filter(PaintCache::contains).count());
			check(kept == 0, kept + " paintings of the old wall are still cached");
			check(context.computeOnClient(client -> PaintCache.pageCount()) == 1, "more pages than the limit");
			log("walked away: the new wall took the page, all " + PER_PAGE + " paintings of the old wall were evicted ("
				+ context.computeOnClient(client -> PaintCache.evictions()) + " evictions so far)");
			screenshot(context, server, "po-new-wall");

			// 4. Back to the default 64 MB: everything fits, and the ones drawn the vanilla way come back.
			context.runOnClient(client -> {
				PaintConfig.get().cacheSizeMb = PaintConfig.DEFAULT_CACHE_MB;
				PaintCache.applyLimit();
			});
			awaitReady(context, AWAY, WALL);
			check(context.computeOnClient(client -> PaintCache.pageCount()) == 2, "the new wall should take 2 pages");

			// 5. Settings through Mod Menu.
			checkModMenu(context);
		}

		context.waitTicks(5);
		check(context.computeOnClient(client -> PaintCache.usedBytes()) == 0, "leaving the world should free the cache");
		log("left the world: cache freed");
		log("ALL CHECKS PASSED");
	}

	private static void checkModMenu(ClientGameTestContext context) {
		Object integration = null;
		for (EntrypointContainer<Object> container : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
			if (container.getProvider().getMetadata().getId().equals("paintoptimizer")) {
				integration = container.getEntrypoint();
			}
		}

		check(integration != null, "no Mod Menu entry point");
		Object entry = integration;
		context.setScreen(() -> newModsScreen());
		context.waitTicks(10);
		context.takeScreenshot("po-modmenu");

		Screen settings = context.computeOnClient(client -> {
			try {
				// Through Mod Menu's interfaces, as Mod Menu itself calls them: the factory is a lambda class.
				Object factory = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi").getMethod("getModConfigScreenFactory").invoke(entry);
				return (Screen) Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory").getMethod("create", Screen.class).invoke(factory, client.gui.screen());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		check(settings instanceof PaintOptionsScreen, "Mod Menu opens " + settings);
		context.setScreen(() -> settings);
		context.waitTicks(10);
		context.takeScreenshot("po-settings");
		context.setScreen(() -> null);
		log("Mod Menu lists the mod and opens its settings");
	}

	private static Screen newModsScreen() {
		try {
			return (Screen) Class.forName("com.terraformersmc.modmenu.gui.ModsScreen").getConstructor(Screen.class).newInstance(new TitleScreen());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** A 10x10 wall of different 64x64 paintings, 12 blocks in front of a player standing at x = baseX. */
	private static void spawnWall(TestServerContext server, int baseX, int seed) {
		for (int gx = 0; gx < GRID; gx++) {
			for (int gy = 0; gy < GRID; gy++) {
				Component text = McPaintEncoder.encode(PaintFont.FULLBRIGHT, painting(seed + gx * GRID + gy));
				String snbt = ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, text).getOrThrow().toString();
				double x = baseX + (gx - GRID / 2) * 1.1 + 0.5;
				double y = -59 + gy * 1.1;
				server.runCommand("summon text_display " + x + " " + y + " 12"
					+ " {billboard:\"center\",background:0,line_width:10000,text:" + snbt
					+ ",transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[0.0625f,0.0625f,0.0625f]}}");
			}
		}
	}

	/**
	 * A picture nobody else has: 8x8 blocks in four colours picked by the seed, with the bottom rows
	 * per-pixel noise like a photo (one glyph per pixel). Only a few rows: vanilla 26.3 takes seconds
	 * per frame on a wall of fully noisy paintings, which is the point of the mod but too slow to test.
	 */
	private static int[][] painting(int seed) {
		Random random = new Random(seed * 7919L + 17);
		int[] colours = new int[4];
		for (int i = 0; i < colours.length; i++) {
			colours[i] = 0xFF000000 | random.nextInt(32) * 8 << 16 | random.nextInt(32) * 8 << 8 | random.nextInt(32) * 8;
		}

		int[][] rows = new int[64][64];
		int[] blocks = new int[64];
		for (int i = 0; i < blocks.length; i++) {
			blocks[i] = random.nextInt(colours.length);
		}

		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				rows[y][x] = y < 64 - NOISE_ROWS ? colours[blocks[y / 8 * 8 + x / 8]] : 0xFF000000 | random.nextInt(32) * 8 << 16 | random.nextInt(32) * 8 << 8 | random.nextInt(32) * 8;
			}
		}

		for (int i = 0; i < 24; i++) {
			rows[random.nextInt(64)][random.nextInt(64)] = random.nextBoolean() ? 0 : 0xFFF8F8F8;
		}

		return rows;
	}

	private static List<Display.TextDisplay> wall(Minecraft client, int baseX) {
		List<Display.TextDisplay> list = new ArrayList<>();
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof Display.TextDisplay display && Math.abs(display.getX() - baseX) < 50) {
				list.add(display);
			}
		}

		return list;
	}

	private static int count(ClientGameTestContext context, int baseX, Predicate<PaintBinding> test) {
		return context.computeOnClient(client -> (int) wall(client, baseX).stream()
			.map(d -> ((PaintHolder) d).paintoptimizer$binding())
			.filter(b -> b != null && test.test(b))
			.count());
	}

	private static void awaitReady(ClientGameTestContext context, int baseX, int ready) {
		context.waitFor(client -> wall(client, baseX).size() == WALL && wall(client, baseX).stream().allMatch(d -> d.textRenderState() != null), 600);
		context.waitFor(client -> wall(client, baseX).stream()
			.map(d -> ((PaintHolder) d).paintoptimizer$binding())
			.filter(b -> b != null && b.isReady())
			.count() >= ready, 600);
	}

	private static Path screenshot(ClientGameTestContext context, TestServerContext server, String name) {
		server.runCommand("time set noon");
		context.waitTicks(5);
		return context.takeScreenshot(name);
	}

	/** Share of pixels where any channel differs by more than a few levels. */
	private static double difference(Path a, Path b) {
		try {
			BufferedImage first = ImageIO.read(a.toFile());
			BufferedImage second = ImageIO.read(b.toFile());
			check(first.getWidth() == second.getWidth() && first.getHeight() == second.getHeight(), "screenshots differ in size");
			long differing = 0;
			for (int y = 0; y < first.getHeight(); y++) {
				for (int x = 0; x < first.getWidth(); x++) {
					int p = first.getRGB(x, y);
					int q = second.getRGB(x, y);
					int d = Math.max(Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)),
						Math.max(Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)), Math.abs((p & 0xFF) - (q & 0xFF))));
					if (d > 8) {
						differing++;
					}
				}
			}

			return differing / (double) (first.getWidth() * first.getHeight());
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	/** Average time the client spends on a frame (extracting, rendering, submitting), over 100 frames. */
	private static long frameTime(ClientGameTestContext context) {
		context.waitTicks(20);
		long total = 0;
		for (int i = 0; i < 100; i++) {
			context.waitTick();
			total += context.computeOnClient(Minecraft::getFrameTimeNs);
		}

		return total / 100;
	}

	/** MC Paint's resource pack supplies the canvas fonts vanilla rendering, and the geometry check, need. */
	private static void installResourcePack(ClientGameTestContext context) {
		String zip = System.getenv("PAINTOPT_MCPAINT_RP");
		check(zip != null && !zip.isBlank(), "set PAINTOPT_MCPAINT_RP to MC Paint's resource pack zip");
		try {
			Path packs = context.computeOnClient(Minecraft::getResourcePackDirectory);
			Files.createDirectories(packs);
			Files.copy(Path.of(zip), packs.resolve("mcpaint_rp.zip"), StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			throw new AssertionError(e);
		}

		CompletableFuture<Void> reload = context.computeOnClient(client -> {
			PackRepository repository = client.getResourcePackRepository();
			repository.reload();
			List<String> selected = new ArrayList<>(repository.getSelectedIds());
			selected.add("file/mcpaint_rp.zip");
			repository.setSelected(selected);
			return client.reloadResourcePacks();
		});
		context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 1200);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void log(String message) {
		System.out.println("[PaintOptimizerTest] " + message);
	}
}
