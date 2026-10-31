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
| Mods' entities | `HorizonEntities.java` (live per level, ticked as `tickNonPassenger`, saved in `horizon-java/entities/`, attack/interact), `HorizonEntityRender.java` (the mod's renderer submits into a recording collector: part trees once, poses + matrix + texture per tick), `HorizonSpawner.java` (natural spawning, the levels' biomes), `HorizonPackets.java` (sounds and particles to Horizon's players), mixins `ServerChunkCacheMixin` (a chunk source holding nothing), `PathNavigationRegionMixin`, `BlockScanMixin`, `ItemModelResolverMixin` + `ItemStackRenderStateMixin` (held items), `EntitySelectorMixin`; Rust: `game/java_entities.rs` (draw, aim), `server_mods.rs` (`send_java_entities`, `java_entity_attack`/`interact`, `/summon`) |
| Mods' worldgen | `HorizonWorldgen.java` (also the biome queries: `biomes`, `findBiome`), `HorizonChunks.java` (chunk copies for code that reads chunks: saplings), `HorizonTints.java` (mods' block tints for the mesher) (vanilla's chunk steps on the mirror's level, chunks back as vanilla saves them), mixin `WorldGenRegionMixin`; Rust: `game/java_worldgen.rs` (`JavaTerrain`, wraps `VanillaTerrain` in `world_loading::make_streaming`), mods' biomes in `biome.rs` (`mod_biomes`) and the biome registry (`lan/data.rs`) |
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
   `Player.interactOn`). A command naming a mod's entity type goes to the mods' server
   (vanilla's command); one with `@e`/`@n` runs there first, quietly, with selectors finding
   no players (`runForEntities`), then in Horizon. Sound and particle packets mods send
   mirrored players are encoded with vanilla's codec and sent on as they are
   (`Natives.packet` → `Action::Packet` → `C::Unknown`).

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

## The client's world, textures, styled text

- Every `as(player, …)` stands the player's client in their dimension's client level
  (`minecraft.level`, `LocalPlayer.level`), screens and messages included (Chunk Loaders'
  grid reads `ClientUtils.getWorld()`). Client levels run the mods' constructor injections
  (`HorizonHooks.constructed`), get chunks as copies of Horizon's (`LevelMixin` on
  `Level.getChunk`, `HorizonChunks`; an `EmptyLevelChunk` where Horizon has none), the server
  level's `palettedContainerFactory`, and answer `enabledFeatures`, `recipeAccess`,
  `getScoreboard` from the server level (`ClientLevelMixin`); `sendPacketToServer` drops.
- The `LocalPlayer` carries the server player's id, UUID, profile, eye height and size (Jade's
  ray started at the feet before), and its old positions.
- The headless client has services (the mirrored server's: profiles), a skin manager giving
  each profile's default skin (`SkinManagerMixin`), a texture manager of named views
  (`TextureManagerMixin`, `HorizonTextures`: `getTexture(id)` knows the PNG; `register`
  keeps mods' textures by name), and atlas sprites (`TextureAtlasMixin`, `HorizonSprites`):
  each sprite is made from its PNG and `.mcmeta` (nine-slice), its texture found through the
  atlas's sources (`assets/*/atlases/gui.json`: "single", "directory"), and owns UV square
  k..k+1 so drawn quads name it.
- `HorizonGraphics.Capture` turns textured quads into blits: a named view's texture (with its
  UVs), else an atlas sprite by UV square; `addBlitToCurrentLayer` too (vanilla's nine-slices
  of headless sprites). `blitSprite(Identifier)` of a mod sprite outside gui/sprites with
  nine-slice/tile metadata goes through vanilla's drawing (Horizon has no metadata for it).
  Sprite ops carry their resolved `texture`.
- Dynamic textures (`DynamicTextureMixin`: Chunk Loaders' chunk maps): a named view
  `horizon:dynamic/N`; its pixels go once per version per player as an `{"op":"image"}` before
  the first blit that draws it (`HorizonTextures.imageOp`, `imagesSent`, cleared on join).
  The game stores images as frames arrive (`take_java_images`; a frame can be replaced before
  it's drawn) in 128 atlas slots of 64x64 (`horizon/java{i}`, bigger images shrunk).
- Text runs keep their styles: a `FormattedCharSequence` with any styled run becomes a
  component (`json`), drawn styled by Horizon (Jade's blue italic mod name).
- A click where the mouse wasn't at the last frame draws a frame there first (widgets that
  keep their hover from drawing).
- `HORIZON_JAVA_DEBUG=loop` logs messages between the halves; `=screens` also logs each sent
  screen frame (first 4000 chars); `=hud` logs the block looked at and its state.

## Client HUD mods

- Once players exist, `HorizonClient.clientTick` runs each player's client in turn inside
  `inWorld(player, …)`: `as()`, plus `minecraft.level` = the dimension's client level
  (`clientLevel`: a `ClientLevel` made without its constructor and registered as a mirror, so
  `LevelMixin` gives Horizon's blocks; block entities and writes go to the server level via
  `HorizonServer.serverSide`; entities via `ClientLevelMixin`, without the player's own server
  copy), `minecraft.hitResult` (`pick`: block, or a nearer mirrored entity), the `LocalPlayer`
  standing where the server player stands. Then the client tick, then `sendHud`.
- `hud(player)` draws into a `HorizonGraphics` with `modsOnly`: an op is kept when, walking out
  from the draw call, a mod's frame (a mod class, or a non-Fabric mixin handler merged into a
  vanilla class: `MixinMerged`) comes before Horizon's driver. Drawn: Fabric's HUD elements
  that mods added (`HudElementRegistryImpl` roots, vanilla layers skipped), the vanilla Hud
  parts mods inject into (`HorizonHooks.hudMethods`; `extractPlayerHealth` for the hearts and
  food, in survival/adventure), and handlers on `Hud.extractRenderState` (`fireHud`).
- Frames go `Natives.hudFrame` → `Action::HudFrame` → `horizon:java_hud` (only when changed);
  the client draws them over its HUD (`draw_java_hud`, under titles and chat) and sends its HUD
  size on `horizon:java_screen_input` as window `-2`.
- `HorizonGraphics` also captures GUI elements mods add to the render state themselves
  (`GuiRenderState.addGuiElement`: Jade's float rectangles) by building their vertices:
  untextured quads become fills and gradients; textured ones are dropped (no texture name).
- What the headless client gained for HUD mods: a game renderer whose main camera answers
  from the player (`CameraMixin`, `MinecraftCameraMixin`: camera entity, delta tracker), a
  `GameRenderState`, debug entries, Hud's tab list and boss overlay (none showing), the
  `LocalPlayer`'s attributes, effects, food, fluid state and input (shared with or made for
  its server player), `ClientLifecycleEvents.CLIENT_STARTED`, and the mods' client resource
  reload (`reloadClientResources`: Fabric's CLIENT_RESOURCES reloaders over vanilla's jar and
  the mods' packs; Jade's themes).
- Mods' top-level `textures/*.png` and `textures/hud/` join Horizon's GUI atlas (AppleSkin's
  `icons.png`).
- `HORIZON_JAVA_DEBUG=hud` logs each HUD frame's size and each Fabric HUD element's ops.

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
- Scene coordinates: write `$(p $X+0.5)` (around.sh's `p`), never `$X.5`: for X = -20 that is
  -20.5, a block off. Scratch worlds have seed `SEED` (default 1234) so scenes land the same.
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
- A mod's block write made between ticks (a player's use: `useBlock`) is applied by Horizon
  after the next tick starts; pending writes (`WRITTEN`) stay until Horizon's world shows them
  (a block entity read as air in between was thrown away: a placed Chunk Loader lost its owner).
- The headless client's `LocalPlayer` is set up on each use (`localPlayer`): position, box,
  rotation, `input` (sneak) from the server player. A field it lacks shows as an NPE in a mod's
  client code; Java's own errors (log4j) go to the game folder's `logs/latest.log`, not
  Horizon's.
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
- An entity renderer's name tag reads `Minecraft.player` (`isInvisibleTo`, its team: the
  `LocalPlayer` needs the server player's `gameProfile`): extract inside `HorizonClient.as`.
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
- Looking at blocks: the headless client reads Horizon's server world (`Natives.blockState`,
  the same for client and server mirrored levels). Jade naming a block other than the one
  Horizon's window draws means the server and the window disagree: the worldgen lost-block
  case (a Java-generated chunk landing over a block already set), not the HUD.
- Jade catches its own exceptions (its tick, its overlay) and logs them to its own logger,
  not ours: when it draws nothing, call `WailaTickHandler.tickClient` and
  `OverlayRenderer.renderOverlay*` by reflection to see the throw.
- `ClientLevel.getLevelData()` is its `clientLevelData` (a `ClientLevel.ClientLevelData`), not
  `Level.levelData`; `getEntities()` reads its `entityStorage`.
- `Minecraft.getCameraEntity()` reads `gameRenderer.mainCamera().entity()`.
- `VanillaPackResourcesBuilder.build` gives a `VanillaPackResources`, which is not a
  `PackResources`: use `.fullResources()`.
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
- 26.3 has no `Biome.getMobSettings`: spawn lists are the environment attribute
  `NATURAL_MOB_SPAWNS` (a biome layer). The mirror gets a `Level.biomeManager` from the
  dimension's biome source with Horizon's seed, and `addStaticLayers` for its attributes.
  Fabric applies `BiomeModifications` from a mixin into `MinecraftServer`'s constructor,
  which the mirror skips: `BiomeModificationImpl.INSTANCE.finalizeWorldGen` is called once.
- Block scans (`containsAnyLiquid`, `checkSpawnObstruction`) read chunk sections
  (`BlockScanUtils.sectionBasedScan`): on the mirror they read the level block by block.
- `/summon` with NBT doesn't run `finalizeSpawn` (no equipment); without NBT it does.
- An item an entity renderer holds goes through `ItemModelResolver.updateForLiving` into an
  `ItemStackRenderState`, then `state.submit(pose, …)` from its layer: keep the stack on the
  state, make `isEmpty` false, and record the pose at `submit`.
- 26.3 scans blocks through chunks, not the level (`BlockScanUtils.sectionBasedScan`: a
  sapling's "flowers nearby", a vine's spread): `level.getChunk` must give a chunk.
  `HorizonChunks` copies Horizon's chunk (blocks and biomes in one native call each,
  `Natives.chunkBlocks`/`chunkBiomes`) the first time a call asks, for that call only
  (`Natives.viewEpoch`), follows the call's own writes, and sends writes made on it to the
  level. Mirrored levels now all have `palettedContainerFactory`.
- 26.3's block tints are `BlockColors` lists of `BlockTintSource`s; mods register them through
  Fabric's `BlockColorRegistry` (held in `BlockColorRegistryImpl.map` until `BlockColors`
  exists). `HorizonTints` classifies each by calling `colorInWorld` in a fake world whose biome
  colours are markers; the mesher's `set_mod_tints` takes them (cache file `block-tints.json`,
  content `FORMAT` 9).
- The command parser's biome ids come from the vanilla export's `by_registry` first, not
  `Registries.biomes`: mods' biomes are added to both (`chat.rs` `build_registries`).
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
Also tested: BOP saplings grow (bone meal: a jacaranda), BOP's tinted blocks
(flowering oak leaves, clover, BOP grass) take their biome colours, `/locate biome
biomesoplenty:redwood_forest` finds one (the mods' biome source), far terrain (LOD) has the
mods' biome colours. The spawn search needs nothing: 26.3's spawn origin is the noise
router's climate (TerraBlender doesn't change it), then the spiral reads generated chunks.
`GRAPHICS=file` (a `horizon-graphics.txt`, e.g. `lodDistance:256`) for `launch.sh` turns far
terrain on.

Works (tested): content (blocks with their own states, items, food, crops, light), mod block
logic and block entities, Fabric and vanilla menus in Horizon's window, mods' own screens with
clicks, keys, typing, scroll, tooltips, recipe book; creative tabs with Fabric pages; mod
commands; mods' messages between their two sides; vanilla block entities shared with mods.

Mods' block entity renderers (tested with Farmer's Delight: a stove's food, a cutting board's
item; `SCENE=ber`): each tick, mod block entities within 64 blocks of a player go through the
renderer the mod registered (`BlockEntityRenderers.PROVIDERS`, a context with no block models
or sprites) into the entities' recording collector; they join the entity snapshot as entries
marked `block` (no hit box, no shadow, lit from the block above). Renderers that draw block
models or atlas sprites (signs) draw nothing yet.

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
1. Mods' worldgen gaps: far terrain's blocks and trees are still Horizon's (vanilla's surface
   rules and features under the mods' biome colours); `/locate structure` is Horizon's; a
   Java proto dropped unfinished isn't saved (a neighbour's tree cut at the edge of where the
   player went); block/fluid ticks and post-processing of generated chunks aren't carried;
   the Nether and End through TerraBlender untested.
   Seen once in five `SCENE=ber` runs (with FD, whose features route the overworld through
   Java): the stove placed by the scene was gone a few seconds later, its outline left. A
   chunk generated on the Java side landing after a block was set in it would do that: check
   that a finished proto never replaces a chunk Horizon already has.
2. Chunk Loaders' grid shows (client level, chunk maps), but clicking a cell doesn't toggle
   it yet: the screen takes the press, no cell's `onPress` runs and nothing is sent (the
   cell's owner is the player; look at corelib's `BaseWidget.mousePressed` order and
   `isClickable`). The owner's name next to the head is empty (profile lookup).
   No list of Java mods' settings in Horizon's own menus yet (`/modconfig` is the way in).
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

Block use has a client half too (`HorizonClient.useBlock`, mod blocks only, after the
server's): Fabric's `UseBlockCallback`, the block's `useItemOn`/`useWithoutItem` with the level
answering `isClientSide()`; what it would write to the world (blocks, entities, level events)
is dropped as a client's prediction, the server's change being the one made. Messages a mod's
client code gives its player (`LocalPlayer.sendSystemMessage`, `sendOverlayMessage`) go to the
player's game, and overlay system messages (the server's too, dropped before) reach the action
bar (`Natives.overlay`, `Action::Overlay`).

Mod screens for players of a server: a dedicated server now runs Fabric with the headless client
too (as a LAN host does), so its players' mod screens are drawn there and sent to their games
(tested: Farmer's Delight's cooking pot, a dedicated server and a Horizon client).
`HORIZON_JAVA_SIDE=server` keeps the old dedicated-server Fabric (no client code). A player's
game draws the frames with its own textures: one without the mods' content shows missing
sprites.

Mod entities also spawn naturally (their biomes' spawn lists, per player, under the caps,
creatures every 400 ticks), Horizon's commands reach them (`@e[type=mod:x]`, `/kill @e`),
and their held items, custom name tags, shadows, sounds and death poof show (tested with the
critter fixture, which spawns in every overworld biome and holds a stick).

Custom geometry (GeckoLib's way: `submitCustomGeometry`, the renderer writing vertices
into a `VertexConsumer`) is recorded too (`HorizonEntityRender.mesh`, a `Recorder`
consumer): quads relative to the entity, sent as `"v"` entries of `models` and drawn as they
are (they move with the entity between ticks, the bones themselves step per tick). Entities
and block entities alike (`GeoBlockRenderer` goes the same way). Tested with
`java/fixtures/geomod` (a spinner whose renderer writes posed boxes, a block entity turning
a cube over its block; `tools/java_bridge/fixture_mod.sh geomod …`); a real GeckoLib mod
isn't tested here (this container may not run downloaded mods): GeckoLib's models load
through its client reload listener, which `HorizonClient.reloadClientResources` runs.

Name tags are the renderer's own (`EntityRenderState.nameTag`, made with the camera on the
entity and it under the crosshair, the nearest player as `Minecraft.player`), shown always
when `shouldShowName` says so with nothing under the crosshair, else when looked at; at the
renderer's attachment height. The custom name is the fallback when the renderer fails.

The mirror's files (mods' entities, block entities, saved data) are written whenever Horizon
saves the world (`save_game_metadata` → `events::save_all` → `HorizonServer.saveAll`): its
autosave, `/save-all` and the world closing, besides every 10 s.

Known gaps of mod entities: vanilla mobs don't see them nor they vanilla mobs (a mod's wolf
won't hunt Horizon's sheep); worn armour, fire, leashes and sprite-atlas models
(`UvMapping`) aren't drawn; custom geometry is sent whole each tick (fine for a few
GeckoLib mobs, heavy for many); particles of a mod's own particle types and entity events
other than the poof (a wolf's hearts) are left out; a mod's own sounds need their files in
the content mod; natural spawns pick a height near the player (no heightmap), and spawn on
world generation doesn't happen; in-frame single player is untested.

Client HUD mods (tested headless, survival): Jade's tooltip for the block looked at (box,
name, mod, item icon), AppleSkin's food overlays over Horizon's food bar.

Known gaps: extended (Fabric) menus share the server's menu with the screen (no client copy);
the recipe book lists every recipe (not the player's unlocked ones); sounds from Java's client
side are silent; HUD mods that render with the GPU (Xaero's Minimap: framebuffers,
`RenderSystem.getDevice()`) draw nothing, nor do textured GUI elements mods add directly or
textured quads with a GPU texture of their own (neither a named view nor a headless sprite);
HUD frames go to every player but each player's
client tick shares the mods' static state;
saved data is written every 10 s and when the world saves.

### Profiling

`HORIZON_JAVA_PROFILE=1` logs every 200 server ticks where the time goes: the game's calls into
Java by call site (`game/java_profile.rs`: `Java profile: …`) and the mirror tick's phases and
each worldgen status (`HorizonProfile`: `Java profile (mirror tick): …`). Worldgen runs on
Horizon's workers, so its numbers are time summed over threads, not tick time.

```sh
HORIZON_JAVA_PROFILE=1 MODS=/tmp/pack SCENE=none tools/java_bridge/screen_test.sh \
  'sleep 40' '$C key w down; $C key ctrl down; sleep 30; $C key w up' 'sleep 15'
grep "Java profile" <scratch>/home/logs/latest.log
```

Measured 2026-10-31, 17 mods (FD, BOP, Tom's, Naturalist, Friends&Foes…), 2-core container:
the mirror tick is ~1 ms (spawner ~0.5, client tick ~0.2). Java worldgen is the cost: 25–50 ms
of `minecraft:terrain` (noise) per chunk, features ~10–20, structure starts and references
~20–40 in the first seconds. A mod that adds any overworld biome or feature sends the whole
overworld through Java; making Horizon's Rust noise feed the Java proto is the big win left.

## Working on it

- One task a branch (`bridge-<task>` off `java-bridge`); keep PR-sized steps.
- Commit as the user (`iaxivers`) with the Co-Authored-By line; never commit `.local/`.
- Write comments like the code around: what vanilla does and why the mirror does it.
