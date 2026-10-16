# Paint Optimizer

Fast, memory-capped rendering of [MC Paint](https://modrinth.com/datapack/mc-paint) paintings for Minecraft 26.3 (Fabric, client only).

MC Paint draws every painting as a text display full of special font glyphs: a single 64×64 painting is thousands of letters, and a gallery is hundreds of thousands. Paint Optimizer reads that text back into the picture once, puts it into a shared texture atlas and draws each painting as one quad.

## What it does

- **One quad per painting** instead of thousands of glyphs. Every painting on the same atlas page shares one render type, so the game draws a whole page of paintings in one batch.
- **No text layout for paintings.** Vanilla splits each painting's text into lines and keeps the result per entity; that is the stutter when a gallery loads and a lot of memory. Paintings skip it.
- **Identical paintings are stored once**, however many copies hang in the world.
- **A hard memory limit** (default 64 MB, 16–1024 MB). Paintings live in 1 MB atlas pages and there are never more pages than the limit. When the cache is full, the paintings you saw longest ago are removed first and come back when you look at them again. Paintings being drawn are never evicted: if they do not all fit, the rest is drawn the vanilla way rather than evicting each other every frame. (Minecraft never frustum-culls MC Paint's displays, so "being drawn" means everything within entity render distance; walk away from a gallery and its paintings become the first to go.)
- **Nothing is kept twice.** Pixels go to the GPU and are not kept in Java memory; the limit is all the memory the cache holds on to.
- **Safe fallbacks.** A painting being painted on in MC Paint's studio is left to vanilla until it settles. The first painting of a session is also laid out by vanilla and compared; if a resource pack sizes paintings differently, the mod switches itself off instead of drawing them wrong.
- Leaving a world or switching the mod off frees the whole cache.

## How much faster

Measured by the in-game test: a wall of 100 different 64×64 paintings, each with four rows of photo-like noise, client CPU time per frame:

| | Frame time |
|---|---|
| Vanilla 26.3 | 49–78 ms |
| Paint Optimizer | 2–3 ms |

The noise is what hurts vanilla: every lone pixel is its own glyph, and 26.3 gets slower with each glyph texture it has to sort. With paintings that are noisy all the way through, like real photos, the same wall takes vanilla several seconds per frame.

Pictures are the same as vanilla's: comparing screenshots, 0.01% of pixels differ.

## Settings

Through [Mod Menu](https://modrinth.com/mod/modmenu): *Mods → Paint Optimizer → Config*. The screen is vanilla's own options screen:

| Setting | Default | |
|---|---|---|
| Fast Paintings | On | Off draws paintings the vanilla way and frees the cache. |
| Painting Cache | 64 MB | The most memory cached paintings may take, 16–1024 MB. |

*Clear Cache* empties the cache, and the line above the buttons shows how much is in use. The settings are stored in `config/paintoptimizer.json`.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5+, Java 25
- Fabric API (only its resource loader is used, for the translations)
- Mod Menu, optional, for the settings screen
- The server needs MC Paint, as usual; the mod itself is client-only.

## Building

```bash
./gradlew build
```

The jar is `build/libs/paint-optimizer-<version>.jar`. Unit tests run with `./gradlew test`. The in-game test needs MC Paint's resource pack:

```bash
PAINTOPT_MCPAINT_RP=/path/to/mcpaint_resourcepack.zip ./gradlew runClientGameTest
```

It hangs 200 different paintings, compares screenshots with vanilla rendering, checks the cache never passes its limit and evicts the paintings seen longest ago, and opens the settings from Mod Menu. Screenshots end up in `build/run/clientGameTest/screenshots`.

## Credits

The renderer is ported to 26.3 from [MC Paint Fix](https://github.com/krypt0nmm/mcpaint-fix) by Krypton Dev (MIT); the atlas and the memory limit are new. MC Paint is by Eroxen. MIT licensed, see [LICENSE](LICENSE).

---

## По-русски

Мод для Minecraft 26.3 (Fabric, только клиент) ускоряет отрисовку картин MC Paint и держит их кеш в заданном лимите памяти.

- Каждая картина рисуется одним прямоугольником из общего атласа текстур, а не тысячами букв. Все картины одной страницы атласа рисуются за один проход.
- Для картин не строится текстовая разметка: это и подвисание при загрузке галереи, и лишняя память.
- Одинаковые картины хранятся один раз.
- **Жёсткий лимит памяти**: по умолчанию 64 МБ, можно от 16 до 1024 МБ. Когда кеш заполнен, первыми удаляются картины, которые вы видели давнее всего. Картины, которые сейчас рисуются, не удаляются никогда: если все они не помещаются, лишние рисуются как в ванилле. Майнкрафт рисует все картины MC Paint в зоне прорисовки сущностей, поэтому «давно не виденные» — это картины, от которых вы ушли.
- В тесте со стеной из 100 разных картин кадр занимает 2–3 мс вместо 49–78 мс в ванилле. На «шумных» картинах (как фото) ванилла тратит на такую стену секунды на кадр.
- Пиксели не дублируются в памяти Java: лимит — это вся память, которую занимает кеш.
- Настройки открываются через Mod Menu и выглядят как обычный экран настроек. Их две: «Быстрые картины» и «Кеш картин» (МБ). Ещё есть кнопка «Очистить кеш».
