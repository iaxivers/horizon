# The Java mod bridge (fork `iaxivers/horizon`, branch `java-bridge`)

Fabric Java mods run inside Horizon: a JVM in the game's process runs Fabric Loader with the real
26.3 jar, mods and all. Horizon stays the game: its world, its server, its rendering. The Java
side is a **mirror**: a `MinecraftServer` and levels made without their constructors, whose
blocks are Horizon's, so mods' code runs against Horizon's world. Mods' content (blocks, items,
assets) is converted to Horizon content mods once. Their screens are drawn by Horizon from what
the mod's own client code draws headless.

Goal: big Fabric packs (SkyFactory-sized). Read this page, then work; test headless before
saying something works.

## Where things are

| Piece | Files |
|---|---|
| JVM, JNI natives, Rust→Java calls | `crates/horizon-jvm/src/lib.rs` (natives: `native_*`), `events.rs` (every call into Java: `server_tick`, `use_block`, `menu_click`, `screen_input`, `random_ticks`, `block_entities`, `game_time`, `export_*`), `content.rs` (content build + cache, `FORMAT`), `libs.rs` (Fabric's libraries) |
| Boot | `java/dev/horizonlab/bridge/Boot.java`, `fabric/FabricBoot.java` (Knot, entrypoints, headless client boot), `Natives.java` (Java→Rust) |
| Server mirror | `java/platform/.../HorizonServer.java`: set-up (`setUp`, `level`), the tick (`tick`: players, events, hooks, menus, block entities, frames), menus shown in Horizon's window (`syncMenus`, `SHOWN`), payloads (`payload`), `syncInventories` (end of every call) |
| Mod block entities | `HorizonBlockEntities.java`: `LIVE` (mods' own, ticked, saved in `horizon-java/block_entities/`), `COPIES` (Horizon's vanilla ones as lasting copies, refreshed each tick, written back on change), Fabric load/unload events |
| Ticks, light, data, hooks | `HorizonTicks.java`, `HorizonLight.java`, `HorizonData.java` (data packs, recipes), `HorizonHooks.java` (mods' injections fired by name) |
| Headless client | `HorizonClient.java`: an allocated `Minecraft` (options, font, gui, window, language with mods' translations, sound/text-input managers, game mode), a `LocalPlayer` per player, screens (`openScreen`, client menus, `frame`, `input`, `tickScreen`), client-only screens (`setScreen` via `GuiMixin`, `CLIENT_ONLY`), the client half of item use (`useItem`), the client's tick (`clientTick`), `/modconfig`, `as(player, …)` swaps `minecraft.player` and `gui.screen` |
| Screen drawing | `HorizonGraphics.java` records `GuiGraphicsExtractor` calls as JSON ops (blit, sprite, fill, gradient, text, item, decorations, tooltips, pose, `typing`) |
| Mods' messages | `HorizonLoop.java`: server→client payloads queued and delivered after `syncMenus` (`flush`); client→server run at once. Mixins: `PlayNetworkingMixin` (server side), `ClientPlayNetworkingMixin` |
| Mixins | `java/platform/horizon-platform.mixins.json` (`client` list: headless-client only) |
| Mods' entities | `HorizonEntities.java` (live per level, ticked as `tickNonPassenger`, saved in `horizon-java/entities/`, attack/interact), `HorizonEntityRender.java` (the mod's renderer submits into a recording collector: part trees once, poses + matrix + texture per tick), mixins `ServerChunkCacheMixin` (a chunk source holding nothing), `PathNavigationRegionMixin`, `ItemModelResolverMixin`; Rust: `game/java_entities.rs` (draw, aim), `server_mods.rs` (`send_java_entities`, `java_entity_attack`/`interact`, `/summon`) |
| Mods' worldgen | `HorizonWorldgen.java` (vanilla's chunk steps on the mirror's level, chunks back as vanilla saves them), mixin `WorldGenRegionMixin`; Rust: `game/java_worldgen.rs` (`JavaTerrain`, wraps `VanillaTerrain` in `world_loading::make_streaming`), mods' biomes in `biome.rs` (`mod_biomes`) and the biome registry (`lan/data.rs`) |
| Content conversion | `crates/horizon-port-pack` (`oracle.rs`, `carry.rs`), `HorizonContent.java` (facts, creative tabs), `crates/horizon-client/src/mod_content.rs` (content root) |
| Game side (Rust) | `crates/horizon-client/src/game/java_mods.rs` (start, content, creative), `server_mods.rs` (actions from Java, menus, block entities, random ticks, `JAVA_STATES`, `JAVA_WINDOWS`), `java_screen.rs` (draws frames, sends input, key maps; `Screen::JavaMod` and `CLIENT_WINDOW` for client-only screens) |

## How a tick flows

1. Horizon's server tick → `server_mods.rs` → `events::game_time`, `events::server_tick` →
   `HorizonServer.tick`: the headless client's tick first (`HorizonClient.clientTick`), players mirrored, Fabric events fired, mods' hooks, levels' ticks,
   block entities (`HorizonBlockEntities.tick`), open menus (`stillValid`, `broadcastChanges`),
   screens (`HorizonClient.sendFrames`: tasks, screen ticks, frames), `syncInventories`
   (Horizon's block entities written back, menus shown, mods' messages delivered).
2. Java sends results back as actions (`Natives.*` → `events::Action`), applied in
   `server_mods.rs` (`apply_java_actions`).
3. Horizon's world changes of Java mod blocks reach Java through `world::SET_HOOK` →
   `events::block_entities` (block entities exist from placement).
4. Mods' entities: `HorizonEntities.tick` in the server tick; after it `events::entities`
   gives a JSON snapshot (`HorizonEntityRender.snapshot`), which `send_java_entities` sends
   each connection (its level, within 160 blocks) on `horizon:java_entities` with the model
   trees it lacks. The client keeps two ticks and lerps positions, matrices and poses.
   Attack/Interact packets with ids from `0x7fff0000` go to Java (`Player.attack`,
   `Player.interactOn`).

5. Mods' worldgen: a dimension whose generation mods change (their biomes in its biome
   source, their features in its biomes: `HorizonWorldgen.modded`) is generated on the Java
   side. Horizon's streaming workers call `events::worldgen_proto` (STRUCTURE_STARTS ..
   TERRAIN), `worldgen_decorate` (FEATURES, the 3x3 region held) and `worldgen_finish` (the
   chunk as vanilla's save NBT, read by `save::decode`); vanilla's own `ChunkStep.apply` runs
   each step over a `StaticCache2D` of `Held` holders (the step's neighbours: Horizon's live
   protos, else a cache of lower-status chunks). Far terrain (LOD), `/locate` and the spawn
   search stay on Horizon's `VanillaTerrain`. Other dimensions are Horizon's as before.

## Client-only screens and the client's tick

- `Gui.setScreen` (26.3's `Minecraft.setScreen`) is cancelled in the headless client
  (`GuiMixin`): `HorizonClient.setScreen` makes it the screen of the player whose client runs
  now (`minecraft.player` inside `as()`), calls `added` and `init`, and sends
  `Natives.clientScreen(uuid, true)`. Rust puts `(conn, CLIENT_WINDOW = -1)` in `JAVA_WINDOWS`,
  so frames and input go as a menu screen's; the client opens `Screen::JavaMod` (blurred
  world, the frame drawn, every key to the screen, Escape too). `setScreen(null)` →
  `clientScreen(false)` → `{"window":-1,"close":true}` → back to playing. `setScreen(null)`
  over a menu's screen closes the menu (`closeContainer`).
- The client half of `Item.use`: after `HorizonServer.useItem`'s server half (and its
  `syncInventories`, which delivers what the server sent the client), a mod item's
  `UseItemCallback` listeners and `ItemStack.use` run on the player's `LocalPlayer` with
  `HorizonServer.clientHalf` set, for which `LevelMixin` answers `isClientSide()` true.
- `/modconfig <mod>` opens a mod's settings screen from its Mod Menu entrypoint
  (`ConfigScreenFactory.create(null)`): needs Mod Menu in the mods folder.
- `HorizonClient.clientTick` (each server tick, before it, so the first precedes
  SERVER_STARTING): client tasks, the mods' injections into `Minecraft.runTick` and
  `Minecraft.tick` (`HorizonHooks.fireClient`: `@Inject` at any point, `@ModifyArg` of a
  boolean, from client mixin configs too), then `ClientTickEvents` START and END. The client
  has no level and no player of its own (like a client at its title screen).

## 26.3 facts that cost time

- Keys are SDL3: `KeyEvent(scancode, keycode, mods)`; letters' scancodes from 4, keycodes are
  ASCII (`java_screen.rs`: `sdl_key`, `sdl_keycode`). Shift mod is 3, control 192.
- Mouse buttons count from 1 (left 1, right 3).
- The open screen is `Minecraft.gui.screen()` (not `Minecraft.screen`).
- Screens draw with `extractRenderStateWithTooltipAndSubtitles` into a `GuiGraphicsExtractor`.
- `ServerPlayer.openMenu` runs `initMenu` (menus send their first data) *before*
  `containerMenu` is set: messages wait for `flush`.
- Objects made with `Natives.allocate` have no constructor-set fields; mods' mixins add fields
  set in constructors too (`emptyCollections` fills collections). A new NPE in the mirror is
  usually one more such field: set it where the object is made.
- Javap the real jar instead of guessing: `javap -p -cp .local/minecraft/26.3/jars/client.jar <class>`.
- Fire Fabric events with `fireEach` (one listener at a time), not `invoker()`: through the
  invoker one mod throwing each tick silently stops every listener after it.
- Vanilla's "who sees this" and sync go through the level's chunk source
  (`getChunkSource().blockChanged`, `PlayerLookup.tracking`, `getDataStorage`, `getGenerator`,
  `randomState`): mirrored levels have a bare `ServerChunkCache` that `ServerChunkCacheMixin`
  answers for. Saved data lives in `horizon-java/data/` (`HorizonSavedData`).
- The headless client's `LocalPlayer`s live in the mirror's *server* levels: code that checks
  `isClientSide()` takes the server branch there, and Fabric lookups must not treat a client
  entity as tracked (`PlayerLookupMixin` checks `isMirror(entity)`).
- `MinecraftServer.getDataStorage()` is a 26.x server-wide storage (field `savedDataStorage`),
  besides each level's.
- Chat from Java goes to Horizon as JSON components (`chatJson`): `getString()` on the server
  side gives a mod's translation key (its Language is vanilla's English only).
- Mods' constructor injections (`@Inject` at `<init>` TAIL/RETURN) don't run on mirror objects
  (made without constructors): `HorizonHooks.constructed(object)` runs them, superclass first,
  for mirrored levels once the world's saved data is there (Chunk Loaders' per-level loaders,
  Fabric's attachments). Levels are first made by the content export, before any world.
- `ServerLevelEvents.LOAD` fires for each mirrored level (vanilla: `createLevels`, before
  `SERVER_STARTED`).
- Saved tickets come back deactivated (`TicketStorage`'s codec): vanilla activates them as the
  server prepares its levels; `HorizonTickets.storage` does on first use.
- `Player.isShiftKeyDown` follows the player's sneak key (bit 8 of `tick`'s per-player
  `permissions`): mods' sneak-clicks (Chunk Loaders' owner, wrenches).
- `tools/java_bridge/java.sh` fails when javac does (it used to copy the last jars and go on).
- `Connection.isMemoryConnection()` is true for mirrored players while the headless client runs
  (as singleplayer's): mods skip remote-only work (config sync expecting a configuration phase).
- Entities need more of a level than blocks: `getChunkSource()` (broadcasts, path regions:
  a mirror `ServerChunkCache`), `getWaypointManager()` (removal), `server.debugSubscribers()`
  (`checkInsideBlocks`), `environmentAttributes()` (moon brightness, difficulty: the
  dimension layer only), `getWorldBorder()` (saved data: a default border),
  `isPositionEntityTicking` (entity manager). `getOverworldClockTime()` needs the server's
  `ClockManager`, which the mirror lacks: use `getGameTime()`.
- Vanilla's entity ids count from 1 (`Entity.ENTITY_COUNTER`, not the level): mirrored
  entities get the bridge's ids (`setId`) so they never clash with Horizon's.
- A mirrored player isn't ticked: `detectEquipmentUpdates` (the held sword's damage) and its
  attack strength must be set before `Player.attack`.
- Renderers submit through `SubmitNodeCollector`; every model submit ends in the abstract
  `submitModel(model, state, pose, renderType, light, overlay, tint, UvMapping, outline)`,
  and `ModelFeatureRenderer` calls `model.setupAnim(state)` only then. A `Proxy` collector
  runs the defaults with `InvocationHandler.invokeDefault`.
- The texture of a `RenderType`: `state` (RenderSetup) → `textures` → `Sampler0` → `location()`.
- Widgets' text (buttons, lists) goes through `GuiGraphicsExtractor.textRenderer*()`, an
  `ActiveTextCollector` that adds to the render state directly, not through `text()`:
  `HorizonGraphics` returns its own collector. Before that, vanilla buttons had no labels.
- A screen with `minecraft.level == null` draws the title screen's panorama and opaque menu
  background: `ScreenMixin` skips the panorama and uses the in-world background. That
  background is a 32x32 tile blitted with UVs past 1: `java_screen.rs` tiles such blits.
- Vanilla's server Language has only vanilla's English: `HorizonClient.language` injects a
  `ClientLanguage` with every mod's `assets/*/lang/` (English, then the options' language).
- Screens ask `minecraft.getWindow()` (gui scale, scaled size): an allocated `Window` with
  the GUI size at scale 3, updated on the screen's `size` input.
- Xaero's lib finishes loading (`tryLoadLaterClient`, its registries frozen) in a
  `@ModifyArg` on `Minecraft.runTick`'s `renderFrame` call; without a client frame before
  SERVER_STARTING it failed with "Registry is not frozen yet!".
- `horizon-ctl key <name>` needs an action: `key escape tap`.
- Worldgen mods set up in `MinecraftServer`'s constructor (TerraBlender's `MixinMinecraftServer`
  puts its regions into the level stems' parameter lists and its surface rules into the noise
  settings; Fabric's biome modifications): the mirror skips it, so `HorizonWorldgen.start`
  fires the mods' `<init>` RETURN/TAIL injections (`HorizonHooks.fire`) after giving the
  server `worldGenSettings`, `structureTemplateManager` and `fixerUpper`.
- 26.3's generation pyramid: STRUCTURE_STARTS, STRUCTURE_REFERENCES, BIOMES, TERRAIN (noise,
  surface and carvers in one step: `NoiseBasedChunkGenerator.buildTerrain`), FEATURES. Every
  step but STRUCTURE_STARTS reads structure starts 8 chunks out; TERRAIN reads biomes 1 out.
- `WorldGenRegion`'s constructor takes `StaticCache2D<GenerationChunkHolder>` and reads
  `holder.getChunkIfPresentUnchecked(status)` (the private `futures` array): a subclass that
  fills it with completed futures is all a step needs. `Blender.of` asks
  `isOldChunkAround` of ChunkMap (none on the mirror: `WorldGenRegionMixin`).
- `ServerLevel` needs `structureCheck`, `structureManager`, `uncachedBiomeResolver` and
  `Level.palettedContainerFactory` for generation (constructor-set: `HorizonWorldgen.readyLevel`).
- Horizon's biome table reads `data/*/worldgen/biome` of the content root (mods' after
  vanilla's, so vanilla's keep their ids); the network biome registry lists them in that order.
- `horizon-ctl block X Y Z` now says the biome there (`"biome"`): F3 has no biome line here.
- Commands through `horizon-ctl command` run as the owner's console; server mods' commands
  (and `/summon` of a mod entity) now go to the mods first (`owner_console_command`).

## Build and test

```sh
# Java 25 (JAVA_HOME may point at 21 on these machines; the scripts pick 25)
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 PATH=/usr/lib/jvm/java-25-openjdk-amd64/bin:$PATH
cargo build --profile fast -p horizon-client --features horizon-client/java-mods   # ~5 min after a Rust change
cargo test -p horizon-jvm                                                           # bridge tests
cargo clippy --profile fast -p horizon-client --features java-mods --bins --lib -- -D warnings -A clippy::manual_range_contains
```

The Java sources compile in `horizon-jvm`'s build script: a Java error shows as a failed build
script. Always look at the build's output: a failed build leaves the old binary running.

**Java-only changes: don't rebuild the game.** The jars are baked into the binary, so a full
build relinks the game (~4 min). `tools/java_bridge/java.sh` rebuilds only the jars (~6 s, ~1
min the first time) into `.local/bridge/dev`; a game started with `HORIZON_JAVA_JARS` set to
that folder runs them. `JAVA_ONLY=1 tools/java_bridge/screen_test.sh …` does both. Most bridge
work is Java: iterate there, and batch Rust changes into as few full builds as you can.

Headless, with screenshots (scratch folders only, see CLAUDE.md):

```sh
MODS=~/mods/fd    SCENE=pot      tools/java_bridge/screen_test.sh '$C click-at 141 187' '$C click-at 163 62'
MODS=~/mods/toms  SCENE=terminal tools/java_bridge/screen_test.sh '$C click-at 149 45'
MODS=~/mods/x     SCENE=none     tools/java_bridge/screen_test.sh   # just start, print failures
```

`launch.sh` starts a game, `around.sh` runs steps around the player. Clicks are GUI pixels
(427x240 at 1280x720; a screenshot pixel / 3). The log of a run: `<scratch>/home/logs/latest.log`;
Java's lines start `[java]`. The first start of a mod set builds its content (~20 s, cached in
`.horizon-cache/java-content`; bump `FORMAT` in `content.rs` when the content format changes).

Test mods (Modrinth, 26.3 Fabric): Farmer's Delight Refabricated (blocks, cooking pot screen,
recipe book), Tom's Simple Storage (block entities, terminal screen, client↔server messages),
Naturalist (mobs with ModelPart models, Fabric API only). The repo's own critter mod
(`java/fixtures/crittermod`: goals, a walking model, a spawn egg) builds with
`tools/java_bridge/fixture_mod.sh crittermod ~/critters/critters.jar`; then
`MODS=~/critters SCENE=entity tools/java_bridge/screen_test.sh '$C mouse left click'`
(`ENTITY=naturalist:bear` for another mod's).

## Status

Mods' worldgen (tested with BOP + TerraBlender + GlitchCore, a new world): the overworld
generates through the mods' chunk steps; BOP biomes (orchard, lavender field...) with their
trees, flowers and blocks, read back by Horizon with their biome ids (`horizon-ctl block`).
`MODS=~/bop tools/java_bridge/launch.sh`, then `tools/java_bridge/biomes.py` lists the biomes
around the player with a place of each (`horizon-ctl block X Y Z` gives one).

Works (tested): content (blocks with their own states, items, food, crops, light), mod block
logic and block entities, Fabric and vanilla menus in Horizon's window, mods' own screens with
clicks, keys, typing, scroll, tooltips, recipe book; creative tabs with Fabric pages; mod
commands; mods' messages between their two sides; vanilla block entities shared with mods.

Mod entities (tested with the critter fixture, server thread): `/summon` and spawn eggs make
them, they tick in Java (goals, paths, physics against Horizon's blocks), their own renderers'
models are drawn by Horizon (parts posed each tick, textures from the mods' `textures/entity/`),
players hit them (red flash, knockback, death, removal) and use them, and they are saved and
come back with their chunks.

A 40-jar pack (124 Fabric ids: BOP, Waystones, Farmer's Delight, Tom's Storage, JEI, Jade,
Carry On, Nature's Compass, Xaero's Minimap, Balm, Architectury, Cloth, YACL, Kotlin...; list in
`bridge-pack`'s commits) starts with one logged failure, renders its blocks, runs block use,
server-side item use (`events::use_item`), saved data, translated mod chat, and the pot and
terminal screens still work. `SCENE=pack` in `screen_test.sh` places a row of its blocks.

Client-only screens (tested headless: Nature's Compass's biome list opened by right-clicking
in the air, its clicks and Escape; Xaero's Minimap and Mod Menu settings by `/modconfig`) and
the client's tick (`ClientTickEvents`, mods' `runTick`/`tick` injections; Xaero's "Registry is
not frozen yet!" is gone) work.

Next (top failure of the pack, in order):
1. Mods' worldgen gaps: mod saplings and bone meal fail (`randomTick`: "Should always be
   able to create a chunk!": the mirror's chunk source gives no chunks to the feature);
   far terrain (LOD), `/locate biome|structure` and the spawn search use vanilla's biomes;
   a Java proto dropped unfinished isn't saved (a neighbour's tree cut at the edge of where
   the player went); block/fluid ticks and post-processing of generated chunks aren't
   carried; the Nether and End through TerraBlender untested; "flowering" leaves of BOP
   (an untinted overlay layer) draw grey patches (content conversion, not worldgen).
2. Mods' key bindings with a client level: the client tick runs as each player but with no
   `Minecraft.level` (mods that check it, Xaero's keys, still do nothing). No list of Java
   mods' settings in Horizon's own menus yet (`/modconfig` is the way in).
Then: NeoForge later.

Nature's Compass searches (tested: right-click, pick a biome, Search; it samples and finds,
and Horizon's world has that biome there). The headless client's `LocalPlayer` has the server
player's position, block and chunk position, box and rotation each time it is used; an
unchanged inventory slot keeps its `ItemStack` (mods write to the stack they were used with).

Mods' key bindings (tested with the `keysmod` fixture: `tools/java_bridge/fixture_mod.sh
keysmod <jar>`, G says so in chat): the server tells each player's game once which keys mods'
`KeyMapping`s are set to (`horizon:java_keys`, 26.3 scancodes); the game sends those while
playing with no screen open, and their releases (`bind` on the screen input channel;
`horizon-ctl key` too). Each player's keys are kept (down, clicks not consumed), and the client
tick runs as each player (`Minecraft.player`, their screen) with their keys on the mods'
mappings. With no player, it runs once as before. Gap: a key rebound in Java's
`options.txt` after a player joined isn't sent again.

Mod chunk loaders (tested with the `loadermod` fixture: `tools/java_bridge/fixture_mod.sh
loadermod <jar>`, `/loader ticket|unticket <cx> <cz> <radius>`, `/loader force <cx> <cz>
<bool>`): tickets and `setChunkForced` go to each mirrored level's own `TicketStorage`
(`HorizonTickets`, saved in `horizon-java/data/<dimension>/`, back on reopening). Horizon keeps
the chunks they reach loaded and ticking, as forced chunks (`Streaming::set_ticketed`, not
`/forceload`'s list), a level with only those ticking too. The ticket's levels are vanilla's:
a radius-3 ticket entity-ticks 3x3 chunks, a forced chunk only itself. Gaps: load-only
tickets (no simulation) don't load; a ticket at level 33 loads nothing.

Mod screens for players of a server: a dedicated server now runs Fabric with the headless client
too (as a LAN host does), so its players' mod screens are drawn there and sent to their games
(tested: Farmer's Delight's cooking pot, a dedicated server and a Horizon client).
`HORIZON_JAVA_SIDE=server` keeps the old dedicated-server Fabric (no client code). A player's
game draws the frames with its own textures: one without the mods' content shows missing
sprites.

Known gaps of mod entities: no natural spawning (NaturalSpawner doesn't run on the mirror),
Horizon's selectors (`@e[type=mod:x]`, `/kill`) don't see them, nor do vanilla mobs (a mod's
wolf won't hunt Horizon's sheep); held/worn items, name tags, shadows, fire, leashes and
sprite-atlas models (`UvMapping`) aren't drawn; GeckoLib-style renderers that don't submit
ModelPart models draw nothing; sounds and particles from them are silent; one tick of
snapshot per server tick as JSON (fine for tens of mobs); entities save every 10 s (not at
quit); in-frame single player is untested.

Known gaps: extended (Fabric) menus share the server's menu with the screen (no client copy);
the recipe book lists every recipe (not the player's unlocked ones); sounds from Java's client
side are silent; client HUD mods (Jade, AppleSkin, minimaps) draw nothing in Horizon's window;
saved data is written every 10 s, not on quit.

## Working on it

- One task a branch (`bridge-<task>` off `java-bridge`); keep PR-sized steps.
- Commit as the user (`iaxivers`) with the Co-Authored-By line; never commit `.local/`.
- Write comments like the code around: what vanilla does and why the mirror does it.
