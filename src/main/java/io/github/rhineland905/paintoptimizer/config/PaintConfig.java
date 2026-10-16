package io.github.rhineland905.paintoptimizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.rhineland905.paintoptimizer.PaintOptimizer;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/** The two settings, in {@code config/paintoptimizer.json}. */
public final class PaintConfig {
	public static final int MIN_CACHE_MB = 16;
	public static final int MAX_CACHE_MB = 1024;
	/** The cache size slider moves in steps of this many megabytes. */
	public static final int CACHE_STEP_MB = 16;
	public static final int DEFAULT_CACHE_MB = 64;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static @Nullable PaintConfig instance;

	/** Draw paintings as cached textures; off is plain vanilla rendering. */
	public boolean enabled = true;
	/** Most memory the cached paintings may take. When it is full, the paintings seen longest ago go first. */
	public int cacheSizeMb = DEFAULT_CACHE_MB;

	public static PaintConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(PaintOptimizer.MOD_ID + ".json");
	}

	private static PaintConfig load() {
		Path file = file();
		PaintConfig config = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				config = GSON.fromJson(reader, PaintConfig.class);
			} catch (Exception e) {
				PaintOptimizer.LOGGER.warn("Could not read {}, using the defaults", file, e);
			}
		}

		if (config == null) {
			config = new PaintConfig();
		}

		config.cacheSizeMb = clampCache(config.cacheSizeMb);
		return config;
	}

	/** Rounds to a slider step and keeps it within range, so a hand-edited file cannot ask for 0 or 100 GB. */
	public static int clampCache(int megabytes) {
		int stepped = Math.round(megabytes / (float) CACHE_STEP_MB) * CACHE_STEP_MB;
		return Math.clamp(stepped, MIN_CACHE_MB, MAX_CACHE_MB);
	}

	public static void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(get(), writer);
			}
		} catch (Exception e) {
			PaintOptimizer.LOGGER.warn("Could not save {}", file, e);
		}
	}
}
