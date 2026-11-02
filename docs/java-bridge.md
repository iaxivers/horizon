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
| Ticks, light, data, hooks | `HorizonTicks.java`, `HorizonLight.java`, `HorizonData.java` (data packs, recipes), `HorizonHooks.java` (mods' injections fired by name), `HorizonMixinTable.java` (every patch of every kind, where it runs, which actions go to the mirror) |
| Headless client | `HorizonClient.java`: an allocated `Minecraft` (options, font, gui, window, language with mods' translations, sound/text-input managers, game mode), a `LocalPlayer` per player, screens (`openScreen`, client menus, `frame`, `input`, `tickScreen`), client-only screens (`setScreen` via `GuiMixin`, `CLIENT_ONLY`), the client half of item use (`useItem`), the client's tick (`clientTick`), `/modconfig`, `as(player, …)` swaps `minecraft.player` and `gui.screen` |
| Screen drawing | `HorizonGraphics.java` records `GuiGraphicsExtractor` calls as JSON ops (blit, sprite, fill, gradient, text, item, decorations, tooltips, pose, `typing`) |
| Mods' messages | `HorizonLoop.java`: server→client payloads queued and delivered after `syncMenus` (`flush`); client→server run at once. Mixins: `PlayNetworkingMixin` (server side), `ClientPlayNetworkingMixin` |
| Mixins | `java/platform/horizon-platform.mixins.json` (`client` list: headless-client only) |
| Mods' entities | `HorizonEntities.java` (live per level, ticked as `tickNonPassenger`, saved in `horizon-java/entities/`, attack/interact), `HorizonEntityRender.java` (the mod's renderer submits into a recording collector: part trees once, poses + matrix + texture per tick), `HorizonSpawner.java` (natural spawning, the levels' biomes), `HorizonPackets.java` (sounds and particles to Horizon's players), mixins `ServerChunkCacheMixin` (a chunk source holding nothing), `PathNavigationRegionMixin`, `BlockScanMixin`, `ItemModelResolverMixin` + `ItemStackRenderStateMixin` (held items), `EntitySelectorMixin`; Rust: `game/java_entities.rs` (draw, aim), `java_server.rs` (`send_java_entities`, `java_entity_attack`/`interact`, `/summon`) |
| GPU (mods that draw with the GPU) | `crates/horizon-jvm/src/gpu/` (`mod.rs`: renderpearl's device on wgpu, natives on `dev.horizonlab.bridge.Gpu`; `spirv.rs`: combined samplers split for naga), `HorizonGpu.java` (the renderpearl backend: textures, buffers, transient memory, encoder, passes, pipelines), `HorizonGraphics` (pictures in picture, GPU textures as HUD images), `HorizonClient.guiRenderer` (the game renderer's GPU parts) |
| Mods' worldgen | `HorizonWorldgen.java` (also the biome queries: `biomes`, `findBiome`), `HorizonChunks.java` (chunk copies for code that reads chunks: saplings), `HorizonTints.java` (mods' block tints for the mesher) (vanilla's chunk steps on the mirror's level, chunks back as vanilla saves them), mixin `WorldGenRegionMixin`; Rust: `game/java_worldgen.rs` (`JavaTerrain`, wraps `VanillaTerrain` in `world_loading::make_streaming`), mods' biomes in `biome.rs` (`mod_biomes`) and the biome registry (`lan/data.rs`) |
| Content conversion | `crates/horizon-port-pack` (`oracle.rs`, `carry.rs`), `HorizonContent.java` (facts, creative tabs), `crates/horizon-client/src/mod_content.rs` (content root) |
| Game side (Rust) | `crates/horizon-client/src/game/java_mods.rs` (start, content, creative), `java_server.rs` (actions from Java, menus, block entities, random ticks, `JAVA_STATES`, `JAVA_WINDOWS`), `java_screen.rs` (draws frames, sends input, key maps; `Screen::JavaMod` and `CLIENT_WINDOW` for client-only screens) |

## How a tick flows

1. Horizon's server tick → `java_server.rs` (`tick_java_server`) → `events::game_time`, `events::server_tick` →
   `HorizonServer.tick`: the headless client's tick first (`HorizonClient.clientTick`), players mirrored, Fabric events fired, mods' hooks, levels' ticks,
   block entities (`HorizonBlockEntities.tick`), open menus (`stillValid`, `broadcastChanges`),
   screens (`HorizonClient.sendFrames`: tasks, screen ticks, frames), `syncInventories`
   (Horizon's block entities written back, menus shown, mods' messages delivered).
2. Java sends results back as actions (`Natives.*` → `events::Action`), applied in
   `java_server.rs` (`apply_java_actions`).
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
- Sprites with nine-slice or tile metadata are always split in Java (vanilla's drawing into
  pieces of the headless sprite): Horizon's frame drawing stretches a `sprite` op whole.
  Headless sprites asked for with no atlas get the GUI atlas's location (vanilla's nine-slice
  blits it: a null one threw).
- An atlas blitted with a sprite's own UVs (`blit(atlas, …, u0, u1, …)`: corelib's
  `submitSprite`, its screens' backgrounds) resolves to the sprite by UV square too.
- Text added to the render state itself (`GuiRenderState.addText(GuiTextRenderState)`:
  corelib's `submitText`, Chunk Loaders' owner panel) is captured as a text op in its pose.
- Scissors: `scissor`/`unscissor` ops nest (intersected, through their pose) and the game
  clips what is drawn under them (`clip_since`): Tech Reborn's energy bars draw the whole bar
  and scissor it to the level.
- A mod's own payload sent as a raw packet (`player.connection.send(new
  ClientboundCustomPayloadPacket(...))`: corelib's channels) goes to the mod's client code like
  a Fabric one (`PacketListenerMixin.modPayload`; vanilla's and Fabric's own stay dropped).
  What a client handler schedules (`Minecraft.schedule`: corelib queues its packets) runs
  right after it, still inside `as(player)`: in their client level. Before, Chunk Loaders'
  client copy never got its loaders, so no cell could be toggled.
- The client's services resolve profiles of the players on the server (then usercache.json)
  before Mojang: Chunk Loaders' owner name.
- Jar-in-jar mods with textures (`META-INF/jars/`: RebornCore in Tech Reborn) are carried as
  mods of their own by the content build (`content.rs`, `nested_jars`; FORMAT 10), so their GUI
  textures join the atlas. Before, Tech Reborn's screens drew only vanilla's inventory.
- **The headless client never ran `ClientBootstrap.bootstrap()`** (Minecraft's main does):
  without `SpriteSources`' types every atlas source parsed to nothing, so the real atlases
  held only the missing texture and every baked model drew it. `models()` runs it first.
- `models()`' reload ran its main-thread steps on whichever thread finished a preparation:
  now and then a pool thread ("Rendersystem called from wrong thread": no models, a broken
  GUI atlas, screens failing with "Texture view does not exist"). They're queued and run on
  the client's thread.
- Block entity renderers that submit a block model (`submitBlockModel`, vanilla's and Fabric's
  with a mesh, whose default drops the mesh): its quads become meshes per sprite, UVs made the
  sprite's own (`blockModel`; a mesh quad's sprite found by UV in its atlas), textures
  `ns:textures/<path>.png`. Mods' `textures/block/` join Horizon's GUI atlas for them.
- Blocks with `RenderShape.INVISIBLE` (drawn by their block entity only) are exported
  (`invisible`) and carried with empty models (`<ns>:block/horizon_invisible/*`, children of
  the originals: particle kept; content FORMAT 11), or Horizon drew the model still.
- The client connection has its `minecraft` (Architectury's join event read
  `this.minecraft.player`: NPE at the client's join).
- **A mod's failure is told in chat** (`HorizonFailures.report`): "[Java mods] <mod> failed in
  <where>: <exception>: <message> (the game goes on; the log has more)", to every player, once
  per mod and place, eight in a session at most (then one "more failed" line); failures before
  anyone joined are told at the first join. The mod is the first stack frame in a mod's jar
  (past the game, Fabric, Horizon), or the mixin of a failing handler (`MixinMerged`). Called
  where the bridge catches mods' failures: events (`HorizonServer.fire`), hooks
  (`HorizonHooks`: HUD, client frame and methods, injections), and the client's screens,
  overlay, HUD, ticks, tasks, joins, settings screens and the recipe viewer. Fixture:
  `fixture_mod.sh failmod` (its ticks throw now and then); regress `failmsg`.
- `HORIZON_JAVA_DEBUG=loop` logs messages between the halves; `=screens` also logs each sent
  screen frame (first 4000 chars) and the inventory overlay's input; `=hud` logs the block
  looked at and its state.

## Players, joins and settings (multiplayer)

- `tools/java_bridge/lan_test.sh`: a host with MODS opened to LAN and a guest joining it (its
  own scratch folder), steps with `$H`/`$G`, screenshots of both. Guests get mod screens, HUD
  mods (Jade shows what each player looks at) and REI over their inventory.
- One headless client runs every player's client in turn (`as`), so a mod's static state was
  shared: the host typed in REI's search box and the guest's showed it. `HorizonClientState`
  swaps listed fields per player as `as` changes player (REI's search field, overlay, entry
  list, favourites, remembered screens); a player new to the client starts from the field's
  empty value, which these mods fill lazily. Add a mod's fields there when its client state
  leaks between players. A player leaving drops theirs (`HorizonClient.left`: state, overlay,
  screen, join).
- Client joins run per player (`joined(uuid)`, the first tick each player's client is in the
  world): the mods' injections into `ClientPacketListener.handleLogin` (only Horizon's own
  platform mixins and Fabric's are skipped: a mod under `dev.horizonlab.` was skipped before),
  then Fabric's `ClientPlayConnectionEvents.INIT` and `JOIN` (their injection is Fabric
  networking's, which the headless client lacks). Puzzles Lib's join reads
  `minecraft.getConnection().getConnection()`: each `LocalPlayer` has the client connection,
  which has a `Connection` with no channel; raw packets sent on it go to the mods' server code
  when they are custom payloads (`ClientCommonPacketListenerMixin`), else are dropped.
  Fixture: `fixture_mod.sh joinmod` (says in chat which hooks ran, per player).
- Mods' settings in Horizon's menus: the server sends each player's game the Java mods with a
  settings screen (`horizon:java_config_mods`, `HorizonClient.configMods`, read once); Options
  → Mods lists them under "Java mods"; a click closes the options (a single-player world is
  paused under them, and frames come from the server's tick) and asks the server
  (`{"kind":"modconfig"}`), which opens it as a client-only screen. The screens come from
  mods' "modmenu" entrypoints: without Mod Menu, `HorizonModMenuApi` makes its API's
  interfaces (ModMenuApi, ConfigScreenFactory, UpdateChecker, default methods answering
  "none") with ASM and puts them on the class path first, so those entrypoints load (tested:
  Jade, AppleSkin, Farmer's Delight, Cloth Config, REI via Architectury).
- Settings per player (`HorizonClientConfig`): the objects holding mods' settings (every
  cloth-config AutoConfig holder, Jade's JsonConfig values, AppleSkin's `ModConfig.INSTANCE`) are
  swapped with the rest of a player's client state; a player new to
  the client gets a deep copy (the mod's own objects copied; the game's, the JDK's and
  libraries' shared). On disk, the first player the client serves (the host) owns `config/`:
  after another player's screen input, files changed under `config/` move to
  `config/horizon-players/<uuid>/` and the owner's are put back. Tested on LAN: the guest turns
  AppleSkin's tooltip off and saves; the host's screen and file still say on, the guest's say
  off. Not yet: a guest's saved files aren't loaded at their next join (their session's
  settings start from the host's); mods holding settings elsewhere (Farmer's Delight's
  ConfigValues) are still shared. Add a mod's holder in `holders()`. REI's config object stays
  shared: it holds REI's entry stacks, and a copy of it stopped REI's overlay drawing.
- `HorizonClientState` gives a player new to the client a fresh empty collection for a
  collection field (null before): REI's own reload cleared `lastDisplayScreen` and threw on
  null.
- Dedicated servers: `tools/java_bridge/server_test.sh` starts `horizon-client --dedicated
  --nogui` with MODS in its `mods-java/` (console through a FIFO: `sv "op Player"`) and a game
  that joins it; steps with `$G`, screenshots of the player's game. Its players get mod screens
  (Tech Reborn's machines), HUD mods (Jade with TR's energy) and REI over their inventory and
  over mod screens: the dedicated server runs the same headless client as a LAN host.
- **REI reloads on its own** at a client's join (Architectury's join event, now that joins
  run; on REI's thread): that empties the client's display registry of the server's displays,
  and REI's reload removes the overlay renderer it applied to the open screen (the renderer
  provider applied at INIT_POST), so its list vanished from a mod screen a few seconds after it
  opened. `HorizonRecipeViewer.keepSynced` (each server tick) syncs the displays again when
  the registry shrank, and `afterOwnReload` applies the renderer again per open screen (and
  queues the overlay's reload and the list's search). Regress `reiscreen`.

## Mods' overlay on Horizon's inventory (REI)

- Mod container screens open in vanilla's order (`openScreen`: REI prepared, the GUI's screen
  set, `added`, `init`, the search refreshed), so REI makes its overlay for them too: its list
  draws beside a Tech Reborn machine. `refreshSearch` does nothing while REI has no overlay
  (a recipe view opened first: `getEntryListWidget` threw "No value present").
- **With the headless GPU, atlases are real GPU textures** (render attachments, filled with
  the missing texture). `HorizonGpu.Texture.atlas` marks those made by `TextureAtlas`; Capture
  treats their quads as sprites by UV square, never as GPU images (REI's buttons and Tech
  Reborn's nine-slices drew magenta and black after the GPU merge).

- While Horizon's own inventory (or a container screen of its own) is open with no mod screen
  in its place, the game sends window `-3` input: `open` (GUI size), mouse moves, `close`. Java
  keeps a `HorizonOverlayScreen` per player (an `AbstractContainerScreen` of the player's
  inventory menu that draws nothing itself; vanilla's InventoryScreen needs the player model
  and recipe book) and draws it `modsOnly` each frame (`sendOverlay`, only when changed) to
  `horizon:java_overlay`. The game draws it over the inventory (`draw_java_overlay`).
- Presses outside the inventory's window with nothing carried go to the overlay (and their
  drags and release), as does the wheel there; keys while REI's search box is focused
  (`HorizonRecipeViewer.searchFocused` makes `typing`). R and U over one of Horizon's slots
  send `recipes` with the slot's item (SNBT): REI's recipes/uses view, a client-only screen.
  Escape there goes back to the inventory (`setScreen(HorizonOverlayScreen)` = close, and
  the game returns to its inventory: `JavaOverlay.back`).
- The server sends an empty overlay frame as a player joins: the game only talks to `-3` once
  it knows the server has Java mods.
- Screen input fires Architectury's `ClientScreenInputEvent` PRE/POST around the screen's
  handler (`HorizonScreenEvents`; vanilla's MouseHandler/KeyboardHandler mixins fire them):
  REI takes its clicks, keys and scrolls there, not from the screen.
- REI's item list searched once as its overlay was made, before the displays were in: empty
  (0/1). After its reload `HorizonRecipeViewer.refreshSearch` searches again.
- `modsOnly`'s stack walk skips `dev.horizonlab.platform.generated.*` (the REI graphics
  wrappers), or every REI draw counted as Horizon's.
- The headless Minecraft has a `KeyboardHandler` (allocated): a focused text box resubmits
  its IME preedit through it.

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

## Mods that change vanilla's behaviour (the mixin table, routed actions)

Most of vanilla's code never runs on the Java side: Horizon does it in Rust. A mod's Mixin into
it does nothing unless Horizon fires it or hands the action to the mirror.

- **The table.** At start-up `HorizonMixinTable` reads every mod's Mixin configs (common,
  server and client) and records every patch: `@Inject` at any point, `@Redirect`,
  `@Modify*`, `@Overwrite`, MixinExtras' `@WrapOperation`/`@WrapWithCondition`/
  `@ModifyExpressionValue`/`@ModifyReturnValue`/`@WrapMethod`. Each patched method gets a side
  (`where`): `java` (the mirror or headless client runs it), `fired` (Horizon fires its
  HEAD/TAIL/RETURN injections), `routed` (the action goes to the mirror when patched), `rust`
  (Horizon's; the patch does nothing). The log has a line of sums (`Mixin table: …`) and one
  line per method a mod patches that Rust does (`Mixin table: Rust does Biome.shouldSnow …`);
  the whole table is `horizon-java/mixin-table.tsv` in the game folder.
- **Routed actions** (a mod patches their inside, Fabric API not counted: Horizon fires its
  events itself). Decided per block/item class, cached, logged once (`… goes to the mods`):
  - Breaking a block (`HorizonServer.breakBlock`, Rust `java_break`): a patch of
    `ServerPlayerGameMode.destroyBlock` past its head/end, or of the block's or tool's
    `playerWillDestroy`/`playerDestroy`/`dropResources`/`getDrops`/`spawnAfterBreak`/
    `mineBlock`: vanilla's whole `destroyBlock` runs on the mirror (removal → Horizon's world,
    drops and experience orbs → Horizon's entities, tool wear → slots); Rust does nothing
    more. Otherwise Horizon breaks it after firing `destroyBlock`'s HEAD injections and
    Fabric's BEFORE, and its RETURN/TAIL ones after (`afterBreak`).
  - Starting to dig: Fabric's `AttackBlockCallback` (`attackBlock`, from the START action).
  - Random ticks (`routedRandomTicks`): a vanilla block whose class chain's `randomTick`
    (`growCrops`, `getGrowthSpeed`, `advanceTree`…) is patched, and growing plants (crops,
    stems, saplings, cane, cactus, berries, bamboo, kelp…) when `BlockStateBase.randomTick`
    is (Serene Seasons). horizon-world's `RANDOM_TICK_HOOK` now returns true to take the tick;
    Java runs `BlockState.randomTick` at the end of the level's block tick.
  - Using an item on a block (`useRouted`, `GameModeMixin`): a patch of `useItemOn` past its
    head, the block's `useItemOn`/`useWithoutItem`, the item's `useOn`/`place`, bone meal's
    `growCrop`, `performBonemeal`: vanilla's body runs instead of PASS to Horizon.
  - Item pickup (`itemTouch`) and experience orbs (`orbTouch`, Clumps): an `ItemEntity` /
    `ExperienceOrb` made for the call, `playerTouch` on the mirrored player (the orb's count,
    the player's experience given first), the result back (count left, delay, experience;
    slots). Server-thread/LAN players only (`guest_pickups`, `guest_orbs`); in-frame not yet.
  - An `ExperienceOrb` a mod's code spawns becomes Horizon's orb (`dropItem` of
    `#experience_orb`, the value as the count).
- `HORIZON_JAVA_DEBUG=ticks` logs each random tick the mods run and what it made.
- Second round (`HorizonVanillaActions`, from the table of a 91-jar pack: the most patched
  vanilla actions left were `Player.attack`, item use, crafting results, crop growth):
  - Horizon's own mobs, hit or used by a player (`vanilla_entity`, from `java_entity_attack`
    and `java_entity_interact` for ids below the bridge's): a copy made from the mob's NBT
    (`mob_tree_nbt`) gets Fabric's `AttackEntityCallback`/`UseEntityCallback` and the mods'
    heads of `Player.attack`/`interactOn`. Bits back: taken, swing, removed (the mods
    removed the copy: Horizon removes its mob, no drops). A vanilla mob a mod's code adds
    (`addFreshEntity`) becomes Horizon's (`Natives.spawnEntity` → `Action::SpawnEntity` →
    `load_mob`): Carry On picks a cow up and puts it down. Middle patches of `Player.attack`
    (Better Combat) stay Rust's: the entity is Horizon's.
  - An item used in the air (`GameModeMixin.useItem`): a vanilla item whose class's use (or
    `releaseUsing`, `finishUsingItem`) a mod patches inside goes to vanilla's use on the
    mirror (`useRouted`); the heads of `ItemStack.use` and of `use` on the item's classes are
    fired (`fireUse`), a cancel being the result.
  - A crafting result taken (`menu::CraftedTake`, from `Menu.on_take`; guests in
    `guest_menu_op`, in-frame beside `award_crafted`): vanilla's `ResultSlot.onTake` on a copy
    of the grid (one of each ingredient, so remainders stay in the copy) and the result
    (Architectury's, Balm's, Modonomicon's crafted hooks). What the mods give is sent to the
    guest at once (`LanHub::send_inventory_slots`: the click's answer would take it as known).
  - Crop and sapling growth: covered by the first round (random ticks, bone meal through
    `useRouted`: BCLib and Puzzles Lib patch `BoneMealItem`, Balm `CropBlock`/`StemBlock`).
- Third round:
  - A player's attack (`HorizonCombat`, Rust `java_attack_mob` from `guest_attack`), when
    Better Combat is loaded or a mod patches `Player.attack`'s inside (Farmer's Delight,
    Spell Engine): copies of the target and the mobs within 8 blocks (NBT, Horizon's ids)
    stand in the mirror's level for the call (`HorizonServer.entities` lists them);
    vanilla's `Player.attack` runs with the patches; `CombatCaptureMixin` catches what
    reaches each copy's `hurtServer` and `knockback` (vanilla's bodies don't run on a copy),
    and Horizon hurts and pushes its own mobs with it (`Game::routed_hit`). The weapon's wear
    and exhaustion are the mirror's. Better Combat: a click becomes its attack request (combo
    counted here, reset after 40 ticks; targets from its own `TargetFinder` among the copies;
    `ServerNetwork.handleAttackRequest`). It hands each hit to the connection's
    `handleAttack`, which on a mirrored connection now attacks directly (`GameListenerMixin`:
    the mirror has no packet processor). Better Combat's client half (animations, its own
    swing timing, the HUD) doesn't run: a click is one attack.
  - A hotbar pick (`SetCarriedItem`): the heads of `Inventory.setSelectedSlot` (Carry On while
    carrying); a cancel keeps the old slot and sends it (`SetHeldSlot`).
  - Getting onto a vehicle (after the guest's interact boarded one): the heads of
    `Entity.startRiding` with a copy of it (a mob from its NBT, a boat or cart by type); a
    cancel takes the guest off (`stop_riding`).
- Fourth round:
  - Getting off (a guest sneaking in a boat, cart, mount; `guest_vehicle_tick`): before the
    seat logic, the heads of `LivingEntity.stopRiding`, `Entity.stopRiding`,
    `Player/Entity.removeVehicle` run with the player sitting on a copy of its vehicle
    (`HorizonVanillaActions.dismount`, the `vehicle` field set by reflection); a cancel keeps
    it seated (its shift is dropped for the tick). Once off, their RETURN/TAIL run.
  - Better Combat's sweep: the mirror level's box queries (`HorizonServer.entities`'
    `get(box, ...)`) now list the visiting copies too, so its `TargetFinder` finds every mob
    in the swing, not just the cursor's. Passive mobs aren't swept (its relation rules).
  - A player's fall damage (`fall_damage`, when a mod patches `causeFallDamage` or
    `calculateFallDamage`: Moving Elevators, Mythic Upgrades, Balm's and Puzzles Lib's
    events): the server sets `PlayerMotion::route_fall` for a guest's moves; a landing that
    would hurt leaves `FallEvent::Routed` instead (the statistic stays Rust's), and after the
    moves the game asks `HorizonVanillaActions.fall`: vanilla's `causeFallDamage` on the
    mirrored player, `calculateFallDamage` answered with Horizon's safe distance and damage
    multiplier attributes (`FallCaptureMixin`: the mirror has no effects or attributes), the
    damage reaching `hurtServer` caught. Horizon deals it with vanilla's sounds
    (`MotionWorld::routed_fall_damage`). A mace's or wind charge's impulse stays Rust's;
    in-frame single player isn't routed.
  - A player's new effect (`Game::add_player_effect`: potions, food, totems, `/effect`): the
    heads of `LivingEntity.addEffect` with the instance; a cancel drops it. Beacons,
    conduits, raids' Bad Omen and the Warden's darkness add theirs directly (not asked).
  - Precipitation (`ServerLevel.tickPrecipitation`), when a mod patches the biomes' weather
    (`Biome.shouldSnow`, `shouldFreeze`, `getPrecipitationAt`...: Serene Seasons): the game
    keeps each column's top (`GameLevel::precipitation` → `java_precipitation`) and, at the
    end of the level's block tick, asks the mirror's biome (`HorizonVanillaActions
    .precipitation`), then runs its own tick with those answers
    (`Behaviours::precipitation_tick_with`). Snow lies in the plains in a Serene Seasons
    winter; the sky still rains in Horizon's window (the client's biome weather is Horizon's).
  - Performance mods (Lithium, FerriteCore, ModernFix...: `HorizonMixinTable.PERFORMANCE`)
    never make an action the mirror's: Lithium overwrites `Biome.getTemperature`.
- The mirrored player's `tickCount` follows Horizon's ticks: Carry On refuses a second action
  on the tick of the last one, and the mirror's players aren't ticked.
- `HORIZON_JAVA_DEBUG=entities` logs each attack on/use of Horizon's entities, a vanilla mob a
  mod adds (with its stack) and each crafting result's hooks; `=stall` writes every Java
  thread's stack to `horizon-java/stacks.txt` each 30 s (the JVM runs with `-Xrs`: no jstack).
- Fixture `java/fixtures/mixinmod` (`fixture_mod.sh mixinmod <jar>`, which now copies
  `*.mixins.json`): an `@Inject` at INVOKE inside `destroyBlock` (every break drops a
  diamond), an `@Redirect` inside `CropBlock.randomTick` (crops grow in the dark), a HEAD
  cancel of `ItemEntity.playerTouch` (dirt can't be picked up), an INVOKE inside
  `ResultSlot.checkTakeAchievements` (an emerald with every craft), a HEAD cancel of
  `SnowballItem.use` (no throwing), a HEAD cancel of `Player.attack` on pigs.

## Mods that draw with the GPU

26.3's blaze3d GPU API is `com.mojang.renderpearl` (`api/`: `GpuDevice`, `CommandEncoder`,
`RenderPass`, textures, buffers, pipelines), with a backend interface under it
(`backend/api/`: `GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`) that its
OpenGL and Vulkan backends implement. The headless client has a third: `HorizonGpu`, whose every
call is a native into `crates/horizon-jvm/src/gpu/` (wgpu, the game's version, on its own
device: Vulkan, else GL). Vanilla's `FrontendGpuDevice` sits on it unchanged: its checks, GLSL
compiled to SPIR-V by shaderc (LWJGL's, from the game's libraries) and reflected; the backend
gets SPIR-V, vertex layouts and uniform lists.

- Textures, views, samplers and buffers are wgpu objects by id. A render pass is recorded and
  encoded when it ends; copies and clears are submitted at once (queue writes in between land
  in order). Readbacks wait for the GPU.
- Shaders: naga reads the SPIR-V after `gpu/spirv.rs` splits each combined image sampler
  (`sampler2D`) into a texture (group 0) and a sampler (group 1, same binding), function
  parameters and calls included, and drops `OpLine`s. Pipelines use wgpu's derived layouts;
  bind groups are made per draw from the bound uniforms (the bindings the shaders use).
  Variants per depth format (Vulkan's backend has with/without depth).
- Coordinates are renderpearl-Vulkan's: NDC y -1 is row 0 (naga's reader flips y for wgpu),
  depth 0..1, so front faces are clockwise for wgpu (`FrontFace::Cw`).
- The headless client gets `RenderSystem.initRenderer(new FrontendGpuDevice(HorizonGpu))` at
  `finish`, then on the first client tick (the render thread is the client's: the server
  thread) the game renderer's GPU parts: a GUI renderer with its GUI projection, lighting,
  overlay and light map textures, a fog buffer (`Fog` uniform, mode NONE), a main render
  target; and `GameRenderer.preloadUiShader` over the client resource manager (vanilla's and
  the mods' shaders: the fallback pipeline cache).
- A frame per player and client tick: the mods' injections at the head and tail of
  `GameRenderer.extract` (`HorizonHooks.fireFrame`), the HUD between, then the dynamic uniforms'
  `reset`, fenced tasks, transient memory reused (`HorizonGpu.endFrame`). Mods' HEAD/TAIL
  injections into `Player.tick` run for the client player each tick.
- Pictures in picture (`GuiRenderState.addPicturesInPictureState`: Xaero's minimap): the
  renderer Fabric's registry made for its class (`PictureInPictureRendererRegistryImpl.FACTORIES`)
  prepares it into its texture, and its blit of that texture becomes a HUD image op
  (`horizon:dynamic/gpu<id>`, read back when it changed) and a blit. Drawn at most 10 times a
  second per player (`PIP_INTERVAL`); in between the last blit is repeated.
- Textures the headless client never uploaded (`HorizonTextures.View`: a mod's PNG, a dynamic
  texture) are uploaded the first time a pass samples them; atlases' views bind white.
- Horizon's window draws images over 64 pixels from 8 slots of 512 (`JAVA_BIG_IMAGE_SLOTS`).
  Only textures a render pass drew into (`HorizonGpu.drawnInto`: not atlases, which
  `TextureAtlas` also draws with passes, nor textures written from memory) become HUD images;
  real atlases' quads go the headless sprites' way. A bigger one (a full-screen map's target,
  the window's 1281x720) is read at most 10 times a second per player and sent shrunk
  (`HorizonGraphics.shrink`, box filter) to 512.
- Mods' blits of a screen-sized target land in `gameRenderState().guiRenderState` (Xaero's
  World Map): the capture's `GuiRenderState` stands there while a screen or the HUD draws.
- `HORIZON_JAVA_GPU=0` turns the device off. `HORIZON_JAVA_DEBUG=gpu` logs textures,
  pipelines and GPU blits (and saves one in 100 as `/tmp/horizon-gpu-N.png`);
  `HORIZON_GPU_TRACE=1` logs each pass (targets, draws, pipelines, its first draw's vertices and
  uniforms, the target's lit pixels after it); `HORIZON_JAVA_GPU_DUMP=dir` writes each
  pipeline's SPIR-V, which `HORIZON_SPV_DIR=dir cargo test -p horizon-jvm gpu` reads back.

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
- Fabric starts the client entrypoints from `Minecraft.<init>` at its first
  `Thread.currentThread()`; mods inject there (`shift = BEFORE`: before the entrypoints,
  `AFTER`: after). SuperMartijn642's Core Lib gathers its `@RegistryEntryAcceptor` fields in
  the BEFORE one, so without it every mod on it (Trash Cans...) fails Fabric's whole start.
  `HorizonHooks` records these as `<start before/after entrypoints>` and `FabricBoot` fires them
  around `main` (`HorizonClient.gameStart`); the headless client now boots before `main`.
- Mods read `Minecraft.languageManager` (Server Translations API in `Language.inject`): the
  headless client has one now.
- Data packs: a new world's packs are vanilla's choice (`MinecraftServer.configurePackRepository`
  with `DataPackConfig.DEFAULT`), not every available pack: mods ship optional built-in packs
  for other mods (BetterEnd's `flamboyant_extensions`) whose recipes fail the whole registry
  load when that mod is missing.
- Constructor-end hooks on mirrored objects (`HorizonHooks.constructed`) now get the
  constructor's arguments the object kept (the field of that type, up the classes: Level's
  dimension type holder, its dimension key) and a level's `LevelStem` from the registries.
  Lithium caches a level's height there: without it every level was 0 tall and every chunk's
  structure step failed (`Size must be positive, was: 1x0x1`). `<init>*` targets count.
- The headless client's block and item models reload (`HorizonClient.models`) never finished
  with a 91-jar pack, holding the first server tick (the world stayed at "Loading terrain"):
  it now gives up after 90 s and logs it (mods reading models get none). The cause, one
  reload stage waiting for good, is still open.
- `launch.sh` stopped (`set -e`) when `/tmp/hz-bridge-scratch` didn't exist.
- In a pack where a mod patches `ServerPlayerGameMode.useItemOn` past its head (Energized
  Power, Core Lib at INVOKE, Mythic Upgrades at RETURN), every use of a vanilla block runs
  vanilla's code on the mirror (`Using minecraft:air on minecraft:stone goes to the mods`):
  chests (Horizon's menu by type) and doors still work there.
- Mirrored players aren't ticked, so their pose doesn't follow the sneak key: `isCrouching()`
  (Veinminer's "sneak to mine a vein") reads the pose, now set to CROUCHING with the key.
- Veinminer (Miraculixx) has no mixin into breaking: it uses Fabric's `AttackBlockCallback` and
  `PlayerBlockBreakEvents.BEFORE`, then calls `player.gameMode.destroyBlock` for each block of
  the vein over the next ticks (vanilla's whole break on the mirror).
- Serene Seasons' `RandomUpdateHandler` (snow and ice by season) walks
  `ServerChunkCache.chunkMap.forEachBlockTickingChunk`: null on the mirror, fails each tick.
- `MinecraftServer.weatherData` (Serene Seasons reads `ServerLevel.getWeatherData`): clear.
- A recipe display with a null category (a mod's recipe type) can't cross vanilla's codec, so
  `HorizonClient.recipeBook` skips it; otherwise the whole client recipe book fails.
- REI: its client fills displays only from the server's sync packet (raw
  `player.connection.send`, which Horizon doesn't deliver) and builds them on a server it finds
  through `Minecraft.getSingleplayerServer()`. `HorizonRecipeViewer` reloads REI's plugins
  with the mirror as that server, then copies the server registry's displays into the client's
  as synced ones (what `DisplaySyncPacket` does). REI's registry access comes through the
  client's connection: the search connection now has the server's registries and is the game
  mode's connection.
- REI draws through its own `GuiGraphics.of(graphics)` (a second extractor on the same render
  state), so HorizonGraphics' overrides were skipped: `ReiGuiGraphicsMixin` returns a class
  made at run time (`HorizonGraphicsWrappers`, ASM) that extends REI's and forwards every
  recorded method and `pose()` to the HorizonGraphics. Access wideners make vanilla's private
  `innerBlit` public *final*: no override, so `GuiGraphicsMixin` forwards it. GUI atlas sprites
  mods look up themselves (`getAtlasOrThrow(GUI).getSprite`) are made on demand (name only)
  and drawn by name. `setScreenAndShow` draws a frame at once: `renderFrame` is a no-op
  headless. Screens that read `mouseHandler.xpos()` get Horizon's mouse.
- Field initializers merged from mixins, on the headless client's player, run in this order:
  empty collections, mods' factory methods returning a field's type (`create...`: Player
  Animation Lib's animation manager fills its layer map), the server player's fields, a mod
  type's constructor taking the player, then the constructor-end hooks, which now include
  client mixins' (`HorizonHooks.read(..., constructorsOnly)`), with the player in a client level.
- New worlds' dimensions: the preset's under the data packs' own (`WorldDimensions.bake` with
  `context.datapackDimensions()`); before, a pack's `dimension/overworld.json` was ignored.
- `Connection.isConnecting()` is `channel == null`: a mirrored connection says it isn't.
- Mirrored levels now have a chunk map (`HorizonServer.chunkMap`, made without its
  constructor, then its constructor-end hooks with a world storage rooted in `horizon-java/`):
  GraphLib (Extended Drawers) keeps its graph storage there. `ChunkMapMixin` makes
  `forEachBlockTickingChunk` walk nothing (Horizon random-ticks its own chunks).
- Field initializers in mixins are merged into constructors: an object made without its
  constructor has those fields null (Spell Engine's `castController` on LocalPlayer, Trinkets'
  attachment on LivingEntity). The headless client's player runs its constructor-end hooks,
  takes the server player's mod-typed fields and makes mod objects that have a constructor
  taking the player. `emptyCollections` now also fills concrete `java.util` collections.
- Mods' data-pack functions (`HorizonFunctions`): the load/tick tags and `/schedule` run on the
  mirror. Vanilla's `/forceload add` loads the chunk at once (`setChunkForced` calls `getChunk`)
  and packs rely on it; Horizon can't, so the run stops (`FunctionsMixin` drops the queue) and is
  retried each tick until Horizon has the chunk. Forced chunks now load as near as the player's
  own (streaming `Ranges::priority`: vanilla's FORCED ticket is level 31). Trace a pack with
  `HORIZON_JAVA_FUNCTIONS_TRACE=1`; command errors go to Java's own log (`<scratch>/logs/latest.log`,
  "Failed to execute function").
- What functions do that Horizon keeps: `/setworldspawn` (`Natives.worldSpawn`), `/gamerule`
  (the mirror's `NotificationManager` → `Natives.gameRule`), teleports (already). Neighbour
  updates on mirrored levels are Horizon's (`MirrorUpdatesMixin`), so `/place template` works.
- A pack that replaces vanilla's noise settings, density functions, dimensions or biomes, or
  ships its own generator, now sends the dimension through Java (`HorizonWorldgen.modded`):
  void and skyblock worlds.
- Commands through `horizon-ctl command` run as the owner's console; server mods' commands
  (and `/summon` of a mod entity) now go to the mods first (`owner_console_command`).

- blaze3d's GPU classes moved to `com.mojang.renderpearl` (`GpuDevice` is
  `renderpearl.api.device.GpuDevice`); shaders are GLSL 330 compiled to Vulkan SPIR-V with
  `auto_bind_uniforms` (renderpearl rewrites each descriptor to set 0, binding = the uniform's
  index). Vanilla's `core/clouds` uses a texel buffer: no wgpu binding, that pipeline fails.
- `GpuBufferSlice.MappedView.data()` is written by moving its position: write back from the
  memory's own address (`MemoryUtil.memAddress0`), not `memAddress` (all zeros reached the GPU:
  every model-view matrix zero, nothing drawn).
- naga sizes a std140 uniform block rounded to 16 (the 40-byte `Fog` block needs 48 bound):
  buffers are made in 16-byte steps and uniform bindings rounded up.
- Biome tints need the colour maps (`GrassColor.pixels`...: their reload listeners) or every
  grass and leaf is black; a client level without a `BiomeManager` throws in `getBiome`.
- Atlas definitions fail ("Unknown element id: minecraft:directory") and item models too
  ("minecraft:model") until `ClientBootstrap.bootstrap()` has run.
- `DynamicGpuDataStorageMapped` grows its ring every frame unless `getDynamicUniforms().reset()`
  runs at the frame's end.
- Each player's client has its own `ClientPacketListener` (`LocalPlayer.connection`,
  `Minecraft.getConnection()`): mods keep per-client state on it. Its join fires the mods'
  injections into `handleLogin` (no packet) and `handleSetSpawn` (the level's spawn) on it.
  On a dedicated server it has a `ServerData` ("horizon"), as a multiplayer client's.
- Xaero's minimap: its session starts in `ClientPacketListener.handleLogin` and lives on the
  connection (`HudSession.getForPlayer`); the static `XaeroMinimapCore.currentSession` (the
  last joined) is cleared before each join, or the next join ends the previous player's
  session. It names its world by `Minecraft.getSingleplayerServer()` (the mirror, set at the
  join when it is an `IntegratedServer`), or on a dedicated server by the `ServerData` and the
  spawn from `handleSetSpawn`, follows the world from `Player.tick` HEAD, writes its map chunks from
  `GameRenderer.extract` HEAD and draws in a picture in picture (`MinimapPipRenderer`), reading
  `gameRenderer.guiRenderer`'s projection, `lighting()`, `mainRenderTarget()`, a client level's
  `clockManager()` (`ClientPacketListener.clockManager`) and `Minecraft.getResourceManager()`.

- `Minecraft.running` must be true (`isRunning`): mods' own threads loop on it (Xaero's
  World Map's `MapRunner`, started once and gone at once otherwise: "Preparing World Map..."
  for ever).
- A client level's chunks (`Level.getChunk` on a headless client level) are Horizon's copies
  whose `getLevel()` is that client level (JourneyMap maps only chunks of the player's level),
  kept (`HorizonChunks.held`) until Horizon's chunk changes (`Natives.chunkRevision`). Mods
  read them on their own threads, where Horizon's world can't be read (no view: revision -1):
  those get what was made; one never made is wanted and made on the next client tick
  (`serveWanted`). Copies are filled at once for that.
- The login's chunk radius is the client's `serverRenderDistance` (`getEffectiveRenderDistance`):
  0 makes JourneyMap map one chunk.
- `TextureManager.byPath` mirrors what mods register (JourneyMap reads it with an accessor and
  makes a new texture each frame otherwise, which pushes the GPU images out of the window's
  slots). Each new dynamic texture is a new `horizon:dynamic/N`.
- What else the headless client carries for map mods: a monitor (`HorizonMonitors`: SDL's
  `findBestMonitor` otherwise), a debug overlay and a chat component on the HUD, chat
  abilities on the local player, a mouse handler from the start (its window events are
  cancelled: `MouseHandlerMixin`), the client level's spawn (`ClientLevelData.setSpawn`), an
  empty player list on each connection, a camera at the origin with no player.
- JourneyMap remakes its full-screen map's buttons, and clears its map, every frame while a
  button's texture `hasImage()` is false: that needs `AbstractTexture.texture` set, which the
  headless dynamic textures now have (`HorizonTextures.Texture`, a GPU texture of nothing).
- `TextureManager.getTexture(id)` of a resource is a `SimpleTexture` (`HorizonTextures.Named`):
  VoxelMap reads the resource itself for a `ReloadableTexture` and gets nothing otherwise.
- `GameRenderer.featureRenderDispatcher` (real render buffers, models, atlases, font) is made
  with the models: VoxelMap's `SubmitPass` draws its HUD through `prepareFrame` and
  `renderAllFeatures`.
- `ClientLevel.hasChunk` is Horizon's (a held copy exists): VoxelMap maps only chunks it has.
- Threads without a view (mods' own: VoxelMap's map thread) read blocks from the held client
  chunks (`HorizonServer.blockState` falls back to `HorizonChunks.heldBlock`); before, every
  block there was air (the map stayed black).
- Text components are resolved on the Java side (`getVisualOrderText`, styles kept): a LAN
  guest's or a dedicated server player's game has no mod's lang files ("jm.minimap.compass.n").
  Vanilla keys in mods' HUD text now come in the server's language.
- Mods' injections into Minecraft's constructor at its options (`INVOKE Options.<init>`:
  Yumi runs its mods' `yumi:client_init` there, Trinkets Updated's) run after the registries'
  freeze (`HorizonHooks.START_OPTIONS`, from FabricBoot): Yumi's common init runs at the
  freeze, and the client init registers receivers of payloads it made.
- The mods' client reload listeners ordered after the models (`getFabricDependencies`) run in
  the models' reload, after them, each failing alone (`failingAlone`); they used to be left out.
- The client player's `levelCallback` is a no-op callback, not `NULL` (a copy of the server
  player's): JourneyMap gives no mapping task to a player not in a level (its map stayed empty).
- Each player's client is told of its chunks (`HorizonClient.clientChunks`): Fabric's
  `ClientChunkEvents.CHUNK_LOAD` for Horizon's chunks within the login radius, nearest first,
  24 a tick, and `CHUNK_UNLOAD` beyond it (or at a dimension change). Diorama maps only what
  it is told of. A client level's chunk cache answers with Horizon's chunks
  (`ClientChunkCacheMixin`), and `ClientLevel.hasChunk` from the chunk's revision without a
  copy (`HorizonChunks.has`: VoxelMap asks hundreds a tick; with copies the server thread
  stalled and LAN guests were dropped).
- While the client's models load, players' clients don't tick (the client stays at its title
  screen), as a vanilla client loads its resources before joining: Diorama reads the models
  once, when its player joins.
- Mods' injections at the head of `GameRenderer.render()` run each frame after `extract`'s
  tail (`HorizonHooks.fireRender`).
- The HUD sends a GPU texture as an image when a render pass drew into it or it was written
  from memory (`writeToTexture`: Diorama's tiles), unless it is an atlas (`Texture.atlas`).
- VoxelMap and JourneyMap together: VoxelMap's client init fails registering a payload type
  JourneyMap registered (probably the same on real Fabric); VoxelMap then has no world-id
  packets.
- The keys mods bind are read again every 100 server ticks and resent when they change
  (JourneyMap registers its keys at its first client tick, after the first join).
- **Mixin strips the injector annotations off merged handlers**: only `@MixinMerged` (mixin,
  priority) is left on the target's method. What a handler is (`@WrapOperation`, its `at`) is
  read from the mixin class in the mod's jar (the census, `HorizonHooks.scan`).
- Mods' `@WrapOperation`s on calls inside a constructor don't run on mirror objects either:
  `HorizonHooks.constructed` runs them (levels, chunk maps; not entities) before the TAIL
  injections, the receiver and arguments from the object's fields, an `Operation` calling the
  wrapped method. Biolith seeds its biome noise in one in ServerLevel's (without it every Java
  worldgen chunk failed with an NPE in `OverworldBiomePlacement.getLocalNoise`).
  A wrapped call whose result the mirror makes itself elsewhere runs its wraps there:
  `RandomState.create` in ChunkMap's (Lithostitched's noise router modifiers) runs in
  `HorizonServer.makeRandomState` through `HorizonHooks.wrapConstructorCall`, the handler's
  parameters matched by type (the call's arguments, then the locals it captures: the level,
  the registries), its return value kept.
- Mixins' field initializers (`private RollManager rollManager = new RollManager();`, or a
  static getter: Enderscape's `variables = EnderscapeClient.clientsideVariables()` on the HUD)
  are merged into the target's constructors: `HorizonHooks.initFields` makes them on mirror
  objects (recorded from the mixins' own `<init>` by the census). A getter may have nothing
  yet when the object is made (the mod's client entrypoint runs later): the HUD's run each
  frame while null.
- A client connection's `scoreboard()` is what `ClientLevel.getScoreboard()` answers, and
  `Minecraft.getConnection()` is the player's own connection
  (`HorizonClient.connection(player)`): each needs the shared one's client, network
  connection and a scoreboard (Cardinal Components' client tick, Puzzles Lib's and
  Architectury's handleLogin).
- A Java mod can register its blocks under namespaces other than its id (BuildCraft's
  modules: `buildcraftenergy`, `buildcraftbuilders`...): the content mod declares them under
  each namespace it owns (`data/<ns>/horizon/block/`, the namespace in the manifest's
  `namespaces`), and the port pipeline gives a jar the oracle's facts when its ids are under
  any of them (it looked at the mod id only: every BuildCraft block was unknown).
- The headless client's level reads the server's block entities, so a client handler can
  change the server's state and make it send the same message again (Large Fluid Tank's
  content message): `HorizonLoop.flush` delivers only what was queued when it started (it
  drained until empty: the server thread never left it).
- Mixin configs with a trailing comma in a list read as a null entry (lenient Gson):
  skip it, or the whole config is lost to the census (Simple Pocket Machines').
- The load functions can schedule what a first player needs (Skyblock Infinite places its
  island 2 s after load, `schedule function … 2s`): the first player also waits until the
  events queued when they finished, due within 10 s, have run (`HorizonFunctions.settle`).
- `Minecraft.getConnection()` is `player.connection` (null for a player without one): the
  headless client's players have `HorizonClient.connection(level)`, whose network
  `Connection` is a mirror one (`HorizonServer.clientConnection`).
- The content cache's stamp is the jars' names, sizes and mtimes: copying the jars without
  keeping mtimes (`cp` without `-p`) makes the content again (minutes for a big pack).
  `launch.sh`: `CACHE=dir` keeps it across scratch folders, a `config/` beside `MODS` is copied.
- The world spawn's search (`find_world_spawn`) starts from the generator's origin
  (`ChunkGenerator.getOrigin`, the climate's spawn target): with Java worldgen it is the mods'
  (`HorizonWorldgen.origin`), asked once the first chunk has started their generation.
- `horizon-ctl launch` waits 300 s for the game's socket; `HORIZON_CTL_LAUNCH_TIMEOUT` (s)
  for a pack that makes its content first.

- **The client level needs a connection.** REI (and others) read the client's registries as
  `minecraft.level.connection.registryAccess()` once there is a level; with a null one REI
  silently drops every recipe whose display touches a tag (half of them). Client levels share
  `HorizonClient.connection(level)`, which carries the server's registries.
- `Level.getEntities(Entity, AABB, Predicate)` goes through `getEntities().get(box, consumer)`,
  not `all()`: anything the mirror lends a level for a call must be in the box queries too
  (Better Combat's `TargetFinder` saw only its cursor). `regress.py` now rebuilds a fixture
  jar when its sources are newer than the cached one (a stale `mixinmod.jar` hid a mixin).

Found in the upstream sync (2026-11-01):
- Upstream's content blocks fan their states out over declared `state_properties`
  (`NewBlock::state_combinations`) and rewrite `model-states.json` after `names()`: a block
  with its own states (`own`) must be left out of that pass, or its states shrink to the
  template's one and the game refuses the whole content root ("model state definitions disagree
  with the imported block registry": every mod block unknown).
- Upstream routes a guest's hit on a mob through its own early-returning block in
  `guest_attack`: a hook placed after it never runs. Routed attacks are `java_mob_attack`,
  inside that block.
- A fresh game folder opens on vanilla's first-launch accessibility screen; scripts that drive
  the title screen click Continue first (`new_world.sh`).
- Upstream removed `Levels::parked_chunks` (lent world): `with_java_world` reads parked levels
  through `parked_dims` + `parked_world`.
- `save::decode_with` takes the dimension's `ColumnLayout` (`VanillaTerrain::layout`, now pub).

- **The server can run ticks faster than Horizon loads chunks** (after the upstream sync, a
  catching-up server runs the 40 ticks Skyblock Infinite schedules within a second). Anything
  that holds a player "until N ticks passed" must also wait for the chunks it needs: a new
  player waits for every chunk a function forced (`HorizonFunctions.WAITING_CHUNKS`).

## Build and test

```sh
# Java 25 (JAVA_HOME may point at 21 on these machines; the scripts pick 25)
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 PATH=/usr/lib/jvm/java-25-openjdk-amd64/bin:$PATH
cargo build --profile fast -p horizon-client -p horizon-client-lib --features horizon-client/java-mods   # ~5 min; horizon-ctl is horizon-client-lib's
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

Nether and End (tested 2026-10-31 with BOP, BetterNether, BetterEnd, BCLib, WorldWeaver):
both generate through the mods, the Nether with BetterNether's and BOP's biomes and blocks
(lumabus and golden vines, cincinnasite and nether ores), the End with WorldWeaver's End
source (`WoverEndBiomeSource`: Better End's chorus forest with its pythadendrons; `/locate
biome betterend:chorus_forest` finds one). Three things it took: the new world's dimensions
are the server default's (`HorizonData.defaultDimensions`: DedicatedServerProperties'
WorldDimensionData with the preset WorldWeaver suggests, as its create-world screen picks it),
the mods' `ServerLevel` constructor injections fire (`HorizonWorldgen.levelHooks`: WorldWeaver
seeds its biome sources there) and `MinecraftServer`'s get the constructor's arguments
(`serverArguments`: WorldWeaver's registry-ready events), and Better End's content loads
(its `fabric.mod.json` has raw newlines in strings: `jar::fabric` reads it leniently; its
eternal pedestal's hardness -11 is carried as -1). `world()` runs once a world: the
Nether's start came mid-tick and remade the stores the tick reads.
Fluids: generated chunks bring their scheduled block and fluid ticks and post-processing
marks (`block_ticks`, `fluid_ticks`, `PostProcessing`, read with the save tables): in the
same area 12 flowing water and 50 flowing lava blocks, none without.
Protos dropped unfinished are saved (`worldgen_snapshot`, `worldgen_restore`: blocks, block
entities, entities, decorated) as Horizon saves its own: leaves no log holds
(`distance=7`) after walking away mid-generation and back, three places: 64 before, 4 after (nine 32×32 areas around each).
`/locate structure` asks the mods' generator (`findNearestMapStructure`; its chunks come
from the generation while it runs: `locatingChunk`): `betternether:pyramid`,
`betternether:ghast_hive`, `minecraft:fortress`, and the overworld's villages where the
mods' biomes put them (a taiga village with its bell, not Horizon's plains one).
`tools/java_bridge/launch.sh` now removes the previous run's scratch folder (~1 GB each).

World types from mods (tested 2026-11-01 with One Block 5.1.6 and Grid World Generator
1.1.0; regress case `preset`; `WORLD=none tools/java_bridge/launch.sh` starts at the title
screen, then `tools/java_bridge/new_world.sh "One Block"`): Create New World's World Type cycles Default, Superflat, then the mods' presets in
`#minecraft:normal` (`create_world::mod_presets`: `data/*/worldgen/world_preset/` of the
content root, named by the mods' `generator.<ns>.<path>`). The world keeps the preset's
dimensions and its id (`horizon:preset` in `world_gen_settings`; dedicated:
`level-type=oneblock:oneblock`); the Java side takes that preset's level stems for the world
(`HorizonData.usePreset`/`stem`, read by `world()` from the world folder or one above it:
the tick passes the dimension's folder). A mod's own generator (One Block's void, Grid's
`GridChunkGenerator`) gives the spawn (`worldgen_origin`: getOrigin and getSpawnHeight; the
spawn search waits until Java says whether it generates the overworld) and no far terrain
(Horizon's noise would show land that isn't there). One Block: spawn on its grass block at
0 64 0, `[OneBlock] Your island is at (0, 64, 0)`, breaking it gives the next block (stone,
mangrove planks, wool), its break counter shows. Grid World: the 5-block grid of terrain.
Fixed on the way: the mirror server had no `packRepository` (One Block's generator reads
the selected packs).
`/locate structure #minecraft:bastion_remnant` found nothing because there is no such tag
(vanilla's bastion is `minecraft:bastion_remnant`, found at 599 blocks with the mods'
Nether); an unknown tag now says so (`commands.locate.structure.invalid`).

Surface rules, faster (2026-11-01, bridge-worldgen-speed2): `HorizonSurface` runs
MaterialSystem.buildSurface's walk (`MaterialSystemMixin`, its head) with the same rules on
the same blocks, for less: blocks read and written in the sections directly (ProtoChunk's
getBlockState/setBlockState checked the height, took the section's lock and updated both
generation heightmaps on every write), a write of the state already there skipped, the fluid
check once per state run, and a block's biome (BiomeManager.getBiome: eight seeded corner
hashes) taken straight from the noise biomes when its eight corners agree.
`HORIZON_JAVA_SURFACE_CHECK=1` runs both walks on every chunk (alternating which goes first:
the first fills the noise chunk's caches) and compares blocks, generation heightmaps and
post-processing marks, vanilla's result kept: 0 of 2700 chunks differ with BOP (8.1 ms a
chunk against vanilla's 10.2), 0 of 2600 with BOP + BetterNether + BetterEnd over the
overworld, Nether and End (4.5 against 8.6). `HORIZON_JAVA_SURFACE=vanilla` turns it off;
another mod's mixin into MaterialSystem turns it off too (logged). What's left of the walk
(JFR): the rules themselves (TerraBlender's dispatch and vanilla's condition tree, ~60%) and
the ore veins' density volumes they sample (~20%, vanilla's density functions): changing
those would mean evaluating the mods' rules differently.
Far terrain (LOD) of mods' worlds: the land is Horizon's noise (the mods' when their
generator is vanilla's or on it), the biomes the mods' (as before), and now in the mods'
biomes the land's top block is their surface rules' (`HorizonWorldgen.lodTops`: the rule at
the column's land top, flat around it, water over it to its height; the land top is the
highest run of a block the rules can give, `lodMaterials`, so trees and plants stay). Vanilla
biomes keep Horizon's own (they are vanilla's rules). BOP areas: grass to podzol in coniferous
forests, sand to dirt or gravel by its marshes, dried salt; ~3 ms a batch of columns on a far
terrain thread. A mod's own generator off the noise (One Block) draws its base columns
(`lodColumns`: ChunkGenerator.getBaseColumn): the void stays empty. Grid World is on the
noise; far away its land is cut by the grid too (`HorizonWorldgen.lodMask`: the generator's
`getConfig()` spacings and replacement block, `GridMask` in `java_worldgen.rs`): a column on
two grid lines whole, on one its layers every `ySpacing` blocks, else gone; a coarse far
tile (wider than a spacing) holds lines, so it shows the layers (regress case `gridlod`).
Lost blocks: a column generated again while it is loaded now leaves the loaded one (and
warns); a decorated region's protos of columns already in the world are dropped, and so is a
far proto of a loaded column instead of saved over its save. Repro (`setblock` of 36 marker
blocks right after spawn with FD, 25 s later each read back): 4 lost in one of 17 runs
before (none in the 15 of those that logged a column landing on a loaded one), none in 4
runs since; the race wasn't caught in the act, so these are guards on the paths a proto
could take over a loaded column, not a proven cause. It is now the regress case `lost`
(`tools/java_bridge/lost_blocks.sh`: the repro, one summary line; FD): 0 lost in 4 more runs
(2026-11-01, bridge-worldgen-4). A failure there names each lost block and its chunk.

Worldgen speed (2026-11-01, bridge-worldgen-speed, 2 cores, BOP): where Java worldgen's
CPU goes (JFR, `-XX:StartFlightRecording` in `JAVA_TOOL_OPTIONS`, `duration=` since the game
doesn't exit the JVM): terrain 60% (noise fill 31, surface rules 25, carvers ~4), features
13%, structure starts/references and biomes ~5%. Per chunk, all in (generation, light, mesh,
by thread CPU over 900 chunks): Java ~86 ms, Horizon's own ~61 ms. With the software renderer
the two cores are also the GPU's (llvmpipe takes ~1 core even at `maxFps:10`), so wall-clock
throughput here says little.
- Parallelism: the mirror's `randomState`/`generatorState` (every step asks, from every
  worker) were `synchronized` statics, and the cache of lower-status chunks one synchronized
  LRU map (289 reads a chunk's structure references): both lock-free now. TERRAIN's and
  BIOMES' work runs on the Horizon worker that asked (`HorizonWorldgen.executor`, the
  `buildTerrain`/`createBiomes` hops redirected) instead of vanilla's background executor:
  Horizon's workers are behind the server and window threads and as many as the cores but
  one; the executor's threads are as many again at normal priority. Default from 4 cores;
  `HORIZON_JAVA_TERRAIN=inline|executor` picks. 2 cores: 300 chunks after a teleport, three
  places, executor 33.6/24.9/30.5 s, inline 36.3/24.4/30.4 s (the earlier "inline slower"
  was before the locks went). Not measurable here: a desktop's many cores.
- Surface rules per biome: TerraBlender already compiles its rules per namespace and
  dispatches by biome only where a chunk's 3x3 has biomes of two mods (`possibleBiomes`);
  compiling is ~1% of the surface time. The cost is vanilla's own walk of every stone block
  of the column through the rule tree (setBlockState 21% of it, ore veins' densities 14,
  biome lookups 9): nothing to cache without changing results.
- Skipping Java where a chunk has only vanilla biomes: most of a BOP world is vanilla's
  (4 of 5 places sampled had no BOP biome within 6 chunks), but the result would differ:
  feature seeds are indexes in the order of every feature of every possible biome
  (`FeatureSorter.buildFeaturesPerStep` over the mods' biomes too), so Horizon's decoration
  of a vanilla chunk isn't what the mods' generator makes, and features crossing into a
  Java neighbour would be cut. The gain is ~25 ms of ~86 a chunk. Not done.

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

Vanilla-patching mods (tested headless 2026-11-01, server thread, survival; each is a
`regress.py` case: `veinminer`, `harvest`, `carry`, `seasons`, `orbs`, `bettercombat`,
`snow`, `sweep`; the `mixinmod` fixture's `mixins1`, `attack`, `ride`, `dismount`, `fall`,
`effects`, `slot`, `mixins2`; every routed action has one, and the fixture ones need no
downloaded jars):
- Better Combat: a click with an iron sword is its attack request; the cow takes 6. One swing
  at three zombies in a row hurts them all (`sweep`).
- Serene Seasons' snow: mid winter, rain: the plains' floor gets snow layers.
- The fixture: ten times the damage inside `Player.attack` (a cow dies to one sword hit, a pig
  spared by its head), no minecarts (the boat still takes the player), no ninth hotbar slot,
  no snowball throws, an emerald with each craft, no getting off while holding a stick, no
  fall damage holding a feather (half the distance with a slime ball: 2 for 10 blocks, 7
  with empty hands), players immune to poison, cows that can't be milked (`Player.interactOn`'s
  head); `mixins1`: a broken block also drops a diamond, dirt isn't picked up, wheat lit 8
  (one short of vanilla's 9) grows.
- Veinminer: sneak + one dig breaks a 7-block iron vein, raw iron and experience drop, the
  pickaxe wears 7.
- Right Click Harvest: a ripe wheat right-clicked is replanted (age 0) and drops wheat.
- Carry On: sneak + right-click with empty hands picks a cow up (gone from Horizon's world,
  its name kept), a right-click puts it down. The carried mob isn't drawn in Horizon's hands
  (client rendering); its `Inventory` slot lock and `Entity` riding patches are Rust's.
- Serene Seasons: wheat grows in mid summer, not in early winter (`seasons` writes
  `out_of_season_crop_behavior = 1`; the default lets one tick in six through). Glass over a
  crop makes it a greenhouse (fertile every season). Its biome/weather patches stay Rust's.
- Clumps: orbs' pickup goes through its `playerTouch` (6 orbs of 7: 42 points).
- The `mixinmod` fixture: routed break, crop ticks, pickup (first round); pigs spared, cows
  hit; snowballs not thrown; an emerald with a crafted stack of planks.

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
1. Mods' worldgen gaps: far terrain's trees are still Horizon's (vanilla's features under the
   mods' biome colours; its top blocks are the mods' now); `/locate structure` from one
   dimension into another (`execute in … run locate`) asks the current dimension's
   generator; a world made before the mods keeps the mods' default preset (WorldWeaver's)
   on the Java side, not its own (a world made with a mod's world type keeps that one).
   The tick passes `world()` the dimension's folder and worldgen the world's, so `worldDir`
   flips between the two (the stores are made again each time).
   Seen once in five `SCENE=ber` runs (with FD): the stove placed by the scene was gone a few
   seconds later. Guarded since (Status: Lost blocks); watch for "generated again while
   loaded" in the log.
2. Chunk Loaders: cells toggle (`SCENE=loader`), owner name and panel show, its floating
   core turns and bobs (its block entity renderer's block model).
   Mods' settings: Options → Mods lists the Java mods with a settings screen (see "Players,
   joins and settings").
3. Vanilla patches Horizon still does in Rust (the `Mixin table: Rust does …` lines): biome
   see `mixin-table.tsv`; routed so far: weather and snow, riding on and off, slot picking,
   Better Combat's attack and sweep, players' fall damage and new effects; in-frame single player's pickups aren't routed. Next actions to route by the
   same pattern: mob drops (`LivingEntity.dropAllDeathLoot` past its head), fishing, crafting
   results.
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
Emissive render types (`eyes`, `entity_translucent_emissive`: GeckoLib's glow layers,
spiders' eyes) are sent `"glow"` and drawn at full light.

GeckoLib in the big pack (2026-10-31, the mods' Modrinth dependencies): none of the 72 jars
needs it. The Graveyard (`the-graveyard-fabric`, 4.0.0+mc26.3) does: plain
`GeoEntityRenderer`s (ghoul, revenant, lich, nightmare...) with `TextureLayerGeoLayer` eye
layers, its textures in `textures/entity/`; it also needs `villagertradingplus`. To try it:
GeckoLib 5.5.7 + The Graveyard + Villager Trading Plus + Fabric API in the mods folder,
`/summon graveyard:ghoul ~3 ~ ~ {NoAI:1b}`.

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
GeckoLib mobs, heavy for many); a mod's particle types are drawn as a generic particle
(its sprites, not its provider's motion); in-frame single player is untested.

Game rules and difficulty (2026-11-01): Horizon's rules and difficulty go to the mirror's
server when they change (`events::game_rules` from `tick_java_mods`, `HorizonServer.gameRules`:
`GameRules.set` without the server, so nothing echoes back; `Java mods: game rule … is …` in
the log). The mods' spawner keeps `spawn_mobs` (creatures of generated chunks still come, as
vanilla's), and monsters only off peaceful with `spawn_monsters`; despawning in peaceful,
mobs' griefing and the rest read the same rules. Tested: no critter spawns near the player
with `spawn_mobs` off, ten in 45 s once on.

Mods' player layers (2026-11-01; Carry On's carried mob): each mirrored player has a client
avatar (`HorizonEntityRender.avatar`: a real `RemotePlayer` in its client level, synced each
snapshot: position, rotations, walk and swing, synced data, equipment, inventory, mixins'
fields and Fabric's attachments). The players' renderers (`EntityRenderers.createAvatarRenderers`,
on the context's dispatcher, which is now also `Minecraft`'s with its renderers by type) have
their mods' layers wrapped (`ModLayer`); the snapshot records only what those submit, as an
entry at the player with no box (`"block"`) and an `"owner"`: Rust sends a player its own in
`"own"` (drawn in third person at the player as drawn), the others with the entities. First
person: vanilla's `FirstPersonHandsAndItemsRenderer.submitArmWithItem` per hand, which mods
hook (Carry On draws what is carried and cancels), recorded without vanilla's own arm and
held item; `"hands"` goes to that player (view space, drawn in `hand_geometry` through its
`to_world`), with the hands to hide. Left: the arms' pose mods set (`HumanoidModel.setupAnim`
mixins: Carry On holds its arms out) isn't Horizon's player model's; a carried block isn't
tested (Carry On draws it as an item).
26.3 traps found: Fabric's `UseEntityCallback` and `AttackEntityCallback` fire in the packet
listener, not in `Player.interactOn`/`attack` (the bridge fires them now); mirrored players
weren't ticked, so their `tickCount` stood still (Carry On refuses on "the same tick": it is
advanced each server tick now); Fabric's attachments are a field mixed into `Entity`
(`dataAttachments`), which client copies of a player share now (`HorizonClient.shareAttachments`).

LAN (2026-11-01): `python3 tools/lan_e2e.py --bin-dir target/fast --java-mods <jars> --check
javamobs` (a build with java-mods; the crittermod fixture jar in `<jars>`; on Linux without
vscreen `DISPLAY` and `HORIZON_E2E_HEADLESS=1`): the host summons two critters, the guest's
game shows both, the guest kills one with a sword (gone on both sides), the host kills the
other (gone on the guest's side). Passes.

Natural spawning of mods' mobs (`HorizonSpawner`, 2026-11-01): heights as
`NaturalSpawner.getRandomPosWithin` picks them, up to Horizon's WORLD_SURFACE heightmap
(`Natives.surface`), 6 tries a player a category a tick (48 for creatures every 400 ticks:
vanilla tries every chunk in range). Chunks Horizon generates (`Chunk::fresh`, also the
mods' Java-generated ones) are queued (`horizon_jvm::world::fresh_chunk`, from
`tick_creatures`) and get `spawnMobsForChunkGeneration`'s creature groups of the mods' types
(`Natives.freshChunks`, the biome's `CREATURE_WORLD_GEN_SPAWN_PROBABILITY`). A minute's count
is logged: `Java mods: the last minute's spawns of mods' mobs: N with generated chunks, M near
players` (big pack, walking: ~20 of each).

Client HUD mods (tested headless, survival): Jade's tooltip for the block looked at (box,
name, mod, item icon), AppleSkin's food overlays over Horizon's food bar.

Known gaps: extended (Fabric) menus share the server's menu with the screen (no client copy);
the recipe book lists every recipe (not the player's unlocked ones); sounds from Java's client
side are silent; textured GUI elements mods add directly with a GPU texture of their own
are drawn only when the texture is the headless GPU's (pictures in picture);
HUD frames go to each player (LAN guests too: `tools/java_bridge/lan_test.sh`, tested with
REI, Jade, AppleSkin, Tech Reborn); each player's client tick runs in turn, and the mods' state
listed in `HorizonClientState` is kept per player;
saved data is written every 10 s and when the world saves.

Mods that draw with the GPU (tested 2026-11-01 headless, Xaero's Minimap 26.6.2 alone,
llvmpipe): Xaero's minimap draws on Horizon's HUD at its place: the map of the land around in
its real colours (block textures, grass and foliage tints from vanilla's colour maps and the
biomes of Horizon's chunks, water), its frame, the compass letters, the player's arrow, other
players' dots and the coordinates under it, all through Xaero's own pipelines and shaders on
wgpu. Each player has their own minimap (single player, LAN guests, a dedicated server's
players: tested with a host and a LAN guest, and a guest on `--dedicated`).

What the headless client loads for it at its start (`HorizonClient.models`, ~4 s): the client
bootstrap (`ClientBootstrap`: atlas sources, item model types), `BlockColors.createDefault`, a
real `AtlasManager` and a `ModelManager` reloaded over the client resources with the colour
map listeners (`GrassColorReloadListener`...), and a real `FontManager` (`fonts`): the
client's Font answers with its glyphs from then on (text drawn on the GPU shows). The real
atlases join the headless GUI one in `Minecraft.getAtlasManager()`. Client levels get a
`BiomeManager` over their own chunks with the world's seed.

Map mods (2026-11-01, `bridge-gpu2`, headless, llvmpipe):
- Xaero's World Map 1.47.2 (with the minimap): M opens the full-screen map, drawn by its own
  pipelines into a window-sized target, the explored land around in its colours.
- JourneyMap 6.0.10: its minimap (circle, compass, coordinates, biome, the map in its colours,
  filling in over ~2 minutes as it maps the chunks around) draws on the HUD. J opens its
  full-screen map (the first time its About screen, Escape for the map): toolbars, the map,
  the key list (2026-11-01, `bridge-gpu3`; "Map Zoom: 512" is its normal 1:1). Its server
  side's `LoadedChunkOverlayPlugin` fails each tick (`ChunkMap` of the mirror).
- VoxelMap 1.16.13 (`bridge-gpu3`): its minimap draws (map, frame, arrow, compass,
  coordinates), through the game renderer's feature dispatcher.
- Diorama 0.6.0 (`bridge-gpu-3`): its square minimap draws the map tiles it bakes on the CPU
  from the client's chunks (512 px textures written from memory, blitted to the GUI). Its
  water's colours are to be compared with the real game. XaeroPlus and MiniHUD also draw with
  the GPU; not tried yet.
- Per player (host and LAN guest): Xaero's minimap is each player's own (`bridge-gpu3`);
  JourneyMap's and VoxelMap's too since `bridge-gpu-2` (`HorizonClientState`): JourneyMap's
  minimap and full-screen `MapState`/`MapRenderer` per player (static finals, made non-final as
  the class loads by `mixin/mods/ModsPlugin`), its player cache and labels read again at each
  switch; VoxelMap's `Map` per player (a new one for each, its images re-registered by name at
  each switch, computed on the client's thread when there are several players). JourneyMap's
  `MiniMap` object stays shared (a new one recurses through `UIManager`). Regress case
  `maps-lan` (regress.py runs `lan=True` cases with lan_test.sh).

Left: the client tick is ~35 ms with the minimap on llvmpipe (the frame's start ~25: Xaero's
writer, the HUD ~10: the picture drawn and read back at 10 Hz).

Big pack (tested 2026-10-31, `SCENE=none`, 72 jars, 213 mods with nested ones: the 40-mod
pack plus REI, Modonomicon, Tech Reborn, Energized Power, Storage Drawers, Extended Drawers,
Traveler's Backpack, Trash Cans, Lootr, CC: Tweaked, BetterEnd/BetterNether, Universal Graves,
Serene Seasons, Mutant Monsters...): Fabric starts, 9305 recipes load, the overworld generates
through the mods with no failed chunk. Not available for 26.3 Fabric yet: EMI, Patchouli
(Modonomicon instead), AE2, Mekanism, Create, Sophisticated Backpacks (Traveler's instead).
`/recipes [item]` and `/uses [item]` (the held item without one) open REI's recipe view
(`HorizonRecipeViewer`): vanilla crafting, Farmer's Delight cooking, Tech Reborn's compressor,
Energized Power's recipes draw. The first one reloads REI (~3 s).
REI's item list, search box and R/U keys work over Horizon's inventory (2026-10-31,
`bridge-rei-overlay`), and over mod container screens (2026-11-01, `bridge-client2`). REI
26.3's craftable-filter button has no icon of its own (a tinted button: none is drawn in its
code). Left: GraphLib logs
an error each tick per level (`ServerChunkCache.chunkMap` is null on the mirror); Glitchcore's
client tick wants `LevelExtractor`; Traveler's Backpack's model
reload listener fails.

SkyFactory direction (tested 2026-10-31): no sieve mod exists for 26.3 Fabric (Ex Nihilo,
Ex Deorum, Ex Compressum stop at 26.2 or NeoForge). Skyblock Infinite (void world + island
function) works: the void generates through Java, its function places the island at 6 61 6,
moves the world spawn and the player there. Simply OneBlock works from the first spawn: a
player who joins while the Java mods' load functions haven't run (or the spawn's chunks
aren't there) waits, and joins a second after them, at the spawn they set (`hold_join` in
`game/lan.rs`); its void overworld is the pack's own `dimension/overworld.json`. One Block (theoneblock) and Grid World Generator need their
world preset, which Horizon's world creation can't pick yet. Just Hammers (needs Nanite
Library, undeclared on Modrinth), CB Compressed Blocks, Forgiving Void load.
A 107-jar pack (`big3`: the big pack plus Veinminer, Just Hammers, Compressed Blocks, XP Tome,
Moving Elevators, Simple Copper Pipes, Effortless Building, Nether Chested, Easy Shulker
Boxes, Inventory Sorter, Wizards/Spell Engine/Runes, Jewelry, Archers, Mythic Upgrades,
Forgiving Void, Elevator Mod, Mob Lassos, Hang Glider, FallingTree, Towns and Towers,
Repurposed Structures, Open Parties and Claims, Better Combat): 265 mods, 20179 recipes, REI
shows them (`/recipes justhammers:iron_hammer`). Fixed since (bridge-pack3): Open Parties and
Claims (a mirrored connection isn't "connecting"), FrozenLib capes (no downloads), Better
Combat / Spell Engine / Player Animation Lib on the client player, and the server tick that
froze while the client's models reloaded (now prepared on background workers, applied by
`tasks()`). With Trinkets installed the models' reload never ended (bridge-gpu-1 fixed it:
Trinkets Updated's client init is a Yumi entrypoint run from Minecraft's constructor at its
options, which never ran, so its client manager, whose preparation the models wait for, was
never registered).

A 137-jar pack (`big4t`, 2026-11-01, bridge-pack4: `big3` plus RFTools Base/Builder,
Entangled, Hopper Gadgetry, Copper Hopper, Item Collectors, Simple Magnets, Paladins, Rogues,
Construction Wand, Enderscape, Heart Crystals, Disenchanting Table, Cooking for Blockheads,
Echo Chest, Inventory Management, Storage Delight, Items Displayed, Beautify, Promenade,
Villages and Pillages, Xaero's World Map, Tectonic, Advanced Netherite, Combat Roll): 231,940
block states, 12,929 items; Java starts, the overworld generates through the mods (Tectonic,
Biolith, TerraBlender) with no failed chunk, and the player joins on land 255 s after the
start (`regress.py --pack … bigpack`). Fixed for it: Biolith's biome noise (every Java chunk
failed: its seeding is a `@WrapOperation` in ServerLevel's constructor, now run), the world
spawn's search starts from the mods' generator's origin (Tectonic's land: the vanilla origin
was sea), Puzzles Lib's join (the client player has its connection), Combat Roll's
`RollManager` (mixin field initializers run on mirror objects). Left: Lithostitched's
`wrapNoiseRouter` constructor wrap fails (it wants a registry the mirror's chunk map lacks;
no worse than before, when it never ran); CCA's scoreboard client tick wants a scoreboard;
the vanilla HUD's vehicle hearts (`this.variables` null).
Item Alchemy is out: its library bundles CompatDatapacks, which rewrites every recipe's
`result.item` to `id` and breaks BCLib's recipes (BetterEnd's alloying and infusion: the
registries don't load), a conflict on real Fabric too; Item Alchemy's own recipes need that
rewrite.

Startup (2026-11-01, bridge-worldgen-1; a 110-jar pack like `big4t`'s, 2 cores, content
already made): 193 s to the join before, ~120 s now. Two things: the content root (the
mods' assets and tables merged over the game's, ~40 s to build) was built again on every
run whose working folder differed (its stamp named files through the folder's
`.horizon-cache`, a link to the kept cache in `launch.sh`/`regress.py`): the cache's real
path is used now (`mods::horizon_cache`), so a kept content root is reused; and Java and
Fabric (~75 s of bootstrap, entrypoints and data for this pack) start as soon as the content
root is there (`java_mods::start_early`), beside the rest of the resources' load, instead
of after the window's first frame (`HORIZON_JAVA_EARLY_START=0`: as before). Not beside the
content root's build: the two at once outgrew this sandbox's 6 GB. Left after Java is up
(~35 s here): the overworld's start (~10 s, mostly Fabric's biome modifications asking
TerraBlender's `possibleBiomes` per biome, mod code), then the spawn's chunks. In this pack
Biolith and Lithostitched patch MaterialSystem, so the faster surface walk stays off.

Memory is what limits a pack's size: Horizon keeps several per-state tables (client and its
server thread) and the content build exports every state. The full 157-jar set (`big4`) has
577,094 states (Rechiseled 111,669, Diagonal Fences 86,528, Stoneworks 53,510, XNet 29,207
from 12 blocks, the Macaw's sets ~70,000): it needs more than this sandbox's 8 GB (the 137-jar
pack peaks at 5.8 GB with its content made in the same run). A 32 GB PC runs it. The content
root's per-state tables now keep new rows as text and stream to disk (they took over 6 GB as
trees: `J::Raw` in `mod_content.rs`).

Where the memory goes (2026-11-01, bridge-pack-5; `HORIZON_MEM_LOG=1` logs it after each load
stage, `examples/state_memory_probe` measures a content root's tables; RSS kept, the heap
trimmed first). Vanilla / the 577k-state pack: the block registry and before it 181 / 888 MB,
the state models +28 / +665 MB (the baked models of every variant: Rechiseled's connected
textures, Macaw's shapes), block textures +30 / +175, block facts +39 / +512 (the parsed
`block-behaviours.json` stays until the world rest uses it), pack tables +28 / +176, items +22
/ +243, the renderer +458 / +1044 (llvmpipe: GPU memory is system memory here; on a GPU most
of it is VRAM), loot +9 / +216, worldgen data +33 / +418, the generator and streaming +25 /
+326. The world opens at 5.2 GB, and the JVM's start (heap, metaspace for 155 jars) passes
this sandbox's ~6.2 GB limit. Next: baked models without per-variant copies, block facts
without the behaviour JSON kept, a leaner `BlockState` (a `String` and a `BTreeMap` per
state: ~1 KB). Fixed: `level::Behaviours` parsed `gameplay-facts.json` (314 MB for 208k
states) as a JSON tree, 3.4 GB at its peak (now streamed: 453 MB); loot tables are read when
used; the heap is trimmed after the big loads. Dead ends: heaptrack's interpreter runs out of
memory on this game (tens of millions of live allocations), recording raw or not.

Mods' sounds and particles (2026-10-31): a mod's sound event plays by name (its
`sounds.json` and `.ogg` files are carried into the content mod, which Horizon's sound
registry reads). A mod's own particle type goes as `horizon:java_particles` (JSON: the type's
name and the packet's fields; `HorizonPackets.modParticles`), and the game draws it with the
type's sprites (`assets/<ns>/particles/<name>.json`, textures in the HUD atlas as
`<ns>:particle/...`): `ParticleEngine::add_mod`, a `SingleQuadParticle` animated by age,
moving as sent. Entity events whose particles vanilla's client makes (taming hearts and
smoke, breeding hearts, villager hearts, anger, happiness and sweat, spawn poof, teleport
portal) are sent as vanilla level particles, for the classes that define them. Tested with
the critter fixture: its squeak (`heard` in `horizon-ctl status`) and sparkles when hit.

### The regression run

Before merging into `java-bridge`, play every scene that once worked:

```sh
python3 tools/java_bridge/regress.py --jars ~/hz-test/jars             # all (~10 min)
python3 tools/java_bridge/regress.py --jars ~/hz-test/jars pot rei     # some
python3 tools/java_bridge/regress.py --java-only --jars …             # after a Java-only change
python3 tools/java_bridge/regress.py --list
```

`--jars` is one folder of Modrinth jars (Farmer's Delight, Tom's Storage, Jade, AppleSkin, REI
with Architectury and Cloth Config, Biomes O' Plenty with TerraBlender and GlitchCore, One
Block with CodxLib, Fabric API); a case missing one is skipped, the fixture mods are built. Each case checks what
horizon-ctl reports and the log, and prints `pass`/`FAIL`; screenshots and logs are in
`/tmp/hz-regress/<case>/` (a `look` line names what to see on one). A new scene that works gets
a case in `CASES`. A case's `config` writes mods' config files before the game starts
(`launch.sh`'s `CONFIG`: a folder copied over the game's `config/`). `skyblock` and `oneblock` take Skyblock Infinite's and Simply OneBlock's jars.
`lost` is the lost-block repro (gold blocks set right after spawn, read back 25 s later).
`bigpack` plays a whole pack: `--pack <mods folder>` (a `config/` beside it is the mods'
configs), and checks that Java starts, the registries load, no Java worldgen chunk fails and a
player joins within `--join-within` seconds (default 900; the first run of a set of jars also
makes its content, minutes; it is kept in `/tmp/hz-regress/pack-cache`).

### Play a Prism instance

`tools/java_bridge/prism.sh` lets Prism Launcher install and update the mods while Horizon plays
them: make a Minecraft 26.3 + Fabric instance in Prism, install mods or a modpack as usual, set
the instance's *Settings > Custom commands > Wrapper command* to the script's full path, press
Play. The instance gets a `horizon/` folder (options, worlds, and `mods-java/` linking the
instance's jars, Fabric API left out: the bridge has its own); the mods' `config/` is the
instance's; Prism's signed-in account goes to the game as a session file (read once, deleted).
It runs this checkout's `target/fast` build with `.local/` prepared. `HORIZON_PRISM_DRY=1` sets
up and prints the command; `HORIZON_PRISM_ARGS` adds game arguments. (Idea from the NeuHorizon
fork's NeoForge wrapper.)

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
of `minecraft:terrain` per chunk, features ~10–20, structure starts and references ~20–40 in
the first seconds. A mod that adds any overworld biome or feature sends the whole overworld
through Java.

Worldgen lines now say a step's time per chunk (`(… ms each)`), and TERRAIN's parts
(`worldgen terrain: fill`, `surface`, `carvers`, `noise chunk`: `NoiseFillMixin`). With BOP
alone (2 cores, 2026-10-31) a chunk's TERRAIN is ~31 ms: the noise fill (`doFill`) 11,
surface rules 9.5, carvers 1.4, `createNoiseChunk` 0.4, and ~9 waiting in
`Util.backgroundExecutor`'s queue (buildTerrain and createBiomes hop there). "Terrain = noise"
was wrong: the noise is a third of it.

**Horizon's noise in Java protos** (`HORIZON_JAVA_NOISE=horizon`, off by default): Horizon's
Rust noise fills the proto (`VanillaTerrain::noise_chunk`: biomes, structures and surface
left out) and `NoiseFillMixin` skips `doFill` for it; Java keeps biomes, surface, carvers,
features. It is exact: `HORIZON_JAVA_NOISE_CHECK=1` (with it on) runs vanilla's fill too and
compares, 0 of 2100 chunks differed, villages included. Near a structure that adapts the
terrain (a beard) Java fills (its structures follow the mods' biomes, Horizon's don't);
mods' noise settings, density functions or noises, or a mod structure with terrain
adaptation, turn it off for the dimension. It isn't faster here: Horizon's noise is 12–13 ms
a chunk plus 3 to copy and 1 to apply, against HotSpot's batched `sampleVolume` at 11; time
to generate 300 chunks after a teleport (render distance 8, three places, twice):
Java 85 / 83 s, Horizon 90 / 87 s. Running buildTerrain and createBiomes on the calling
worker instead of the executor (tried) was slower still (96–98 s): more threads at once on
2 cores. Worth measuring on a many-core desktop before turning it on; the copy (98k
`block()` calls) can go faster by section palettes. The other costs left: surface rules
(~10 ms, the mods' biomes: Java's) and features (~15 ms).

Measured 2026-10-31, the 72-jar big pack (same container), standing still after spawn: the
game's calls into Java are 85–170 ms a tick, of which the mirror tick (`server_mods.rs:943`) is
37–84 ms: mod entities 22–75 ms (many mobs from Naturalist, Friends&Foes, Mutant Monsters,
Ecologics spawned around), the client tick 5–10 ms, level hooks ~2 ms; the rest is worldgen
work done on the server thread's calls (proto 35–65 ms). Entities are the first thing to
look at for a big pack.

### Mod mobs' cost (2026-10-31, bridge-mob-perf)

`HORIZON_JAVA_PROFILE=1` now also logs per entity type the tick and snapshot time
(`Java profile (entity ticks)`, `(entity snapshot)`), the block reads (`(block reads)`: sections
fetched from Horizon, kept from earlier ticks, reads served), and the snapshot's bytes
(`Java profile (bytes)`). The load test: 100 critters (`horizon_critters:critter`) summoned on
a grid around the player (dense: 3 blocks apart, all within 16; spread: 9 apart, within 48),
`maxFps:10` so the software renderer leaves the 2 cores alone.

Where the time went at first (dense, 100 mobs: 188 ms a tick):
- AI and physics 130 ms, of which ~70% copying whole chunks: `EntityFluidInteraction.hasFluidAndLoaded`,
  `PathNavigationRegion` and shadows asked for their chunk, and `HorizonChunks` copied all 98k
  blocks of it, again each call (the copies were good for one view only).
- Block reads ~23 ms: one JNI call and a state name string per block (1.9 us each, 13k a tick).
- The snapshot 46 ms (shadows copying chunks again, reflection lookups per entity, Gson's float
  formatting, a held stick's SNBT each tick), Rust 4.5 ms parsing it into a `Value` tree and
  writing it out again per player, the player's game 3 ms parsing it.

What changed:
- Block reads go through whole sections (`HorizonServer.section`, `Natives.sectionIds`: Horizon's
  state ids, unpacked from the section's palette, named once by `stateName`). They are kept
  across ticks while the chunk's `Chunk::block_revision` stays (new on every block set, every
  copy of a chunk; `Natives.chunkRevision`): a mob standing in the same place reads its sections
  once. The view's own epoch is per thread (`Natives.threadEpoch`).
- `HorizonChunks` copies are lazy: block and fluid reads go to the level; only code that reads a
  chunk's sections or heightmaps fills it. `EntityFluidInteractionMixin` answers the fluid check
  from loaded-ness (the exact per-block check follows).
- Lock-free `isMirror`, a flag in front of `WRITTEN`'s lock, cached reflective fields
  (`HorizonClient.set`), cached item SNBT, `HorizonJson` (floats rounded to 1e-4 written by
  integer arithmetic).
- The snapshot is made in the tick's own view (one lend), each entry routed by Java
  (`x y z`, model `keys`, the entry `e`): Rust filters and concatenates the raw JSON per player
  (`serde_json::RawValue`), and the player's game reads it into typed structs.
- Entity activation range: a mod entity with no player within 32 blocks that is idle (no
  target, not hurt, not moving fast, not in a fluid, not riding or ridden) ticks one tick in
  4, and its last look is kept (`"keep"` entries: the game keeps the model, moves the box).
  `HORIZON_JAVA_ENTITY_RANGE` sets the range; 0 turns it off.

Now (same container): dense 100 critters 15 ms a tick on the server thread (AI 7.1, snapshot
5.3, the rest spawner and client tick; block reads 0.1), spread 8–9 ms (AI 2.9, snapshot 2.9).
Per mob: AI ~0.06–0.08 ms, snapshot ~0.03 ms. The container is two shared cores; a desktop
core should be 2–3x faster. Left: the snapshot's fixed part (~2 ms: JSON for all, block
entity scan), the player's game parsing ~1–2 ms on its own thread, and the mobs' own AI.

### The 106-jar pack's mobs (2026-11-01, bridge-mob-perf2)

`HORIZON_JAVA_PROFILE=1` on the big pack (standing, walking, standing; Naturalist, Friends&Foes,
Mutant Monsters, Ecologics spawning): Naturalist's bass ~3.5 ms each a tick, butterflies ~5 ms,
the rest of its mobs 0.3–1.3 ms; 40 mobs 42–75 ms. Not their AI: a Polymer mixin in
`LevelChunk`'s constructor reads every section (`polymer$generatePolymerBlockSet`), which filled
each lazy `HorizonChunks` copy (98k blocks, lithium's state tracking on each), on every
`getChunk` of every mob that moves. Fixed:
- `HorizonChunks.Copy` answers its constructor's mixins with the empty sections the constructor
  made (a `made` flag); copies are kept across ticks while Horizon's chunk revision stays (512 a
  level, LRU; the calls' unapplied writes followed again on reuse).
- The mirror level's entity searches use a chunk-column grid made once a tick
  (`HorizonEntities.near`; each entity in the column it stands in, searches grown by its widest
  box + 8 blocks), the entity list is kept between changes (`HorizonEntities.of` made a new
  list on every `getEntities`).
- The last section read is kept per thread (most block reads follow one another).
- Snapshot: an entity whose look is the same as last sent (standing, resting) goes as `"keep"`
  with its position only; a standing one is drawn every other tick (idle animations at 10 Hz,
  lerped); Rust sends the last whole entry to a player that hasn't had it
  (`send_java_entities`: `looks`, `has`). The snapshot is written straight to one string.
- The profile's block read counter is a `LongAdder` (it took a lock per read).

Now: bass 0.1 ms, butterfly 0.15, whale ~1 (its body parts' collisions), the rest ~0.1–0.2;
the natural 40 mobs ~7–10 ms of ticks + ~2.3 ms of snapshot. 100 mixed Naturalist mobs
summoned within 24 blocks (`mix_load` test: all active): ticks 10.5–11.6 ms (~0.11 each),
snapshot 4.6–4.8 ms (~80 drawn a tick), Rust ~1 ms, payload 22 KB a tick; on two cores shared
with the mods' worldgen (30–45 ms a tick of Java terrain there). Left above 5 ms: the mobs' own
AI (goals, pathfinding, lithium's collision sweeps: ~0.1 ms each here), and a fill each time a
chunk's revision changes (grass, crops).

Big pack traps: the first client tick's model reload (`HorizonClient.models`, with a GPU
device) never ended with 106 jars (a future waiting on nothing; the server tick stopped there
for good): it is now bounded at 30 s. Not mobs, but in the mirror tick: the headless
client's tick, 7–24 ms (the mods' frame-start hooks up to 19 ms in the first minutes, ~5 ms of
HUD).

## Hooks in Horizon's shared files (after the upstream sync)

Since the 2026-11-01 sync with upstream `main`, the bridge's calls from Horizon's own files are
one-line calls into `game/java_server.rs` (upstream deleted `server_mods.rs` with WebAssembly code
mods: our half of it is that file now). Where they are, and what upstream's native mod hook points
(docs/native-mods.md, `game/native_hooks.rs`) could replace later. Not switched yet: each needs the
hook's exact place checked against what the mirror expects.

| Our hook (where) | Native hook point that could replace it |
|---|---|
| `tick_java_server` (game.rs `fn tick`, after `native_tick`) | `native_server_tick` (API 4) end; joins and leaves: `native_player_logged_in/out` (API 57) |
| `apply_java_level_writes` (levels.rs, before each level's tick) | `native_level_tick_pre` (API 23) |
| `RANDOM_TICK_HOOK` (horizon-world `level/tick.rs`) | `native_random_tick` with `handled` (API 33; `Level::before_random_tick`) |
| `SET_HOOK` (render `world.rs` `set`) | `native_block_changed` (only `Level.setBlock` writes; ours sees every write) |
| `HURT_HOOK` (`health.rs`) | `native_living_incoming_damage` (cancel or change the damage) |
| `java_break` / `java_block_event` (game.rs `break_block`, placing) | `native_destroy_block` / `native_place_block` (API 14); a vein miner's whole-break-in-Java has no equivalent |
| `java_attack_block` (lan_actions) | `native_left_click_block` (API 30) |
| `java_use_block`, `java_use_item` (lan_actions) | `native_right_click_block` (API 21), `native_use_item_on` / `native_use_item` (API 13) |
| `java_attack_mob`, `java_entity_interact` | `native_attack_entity` (API 22), `native_interact_entity` (API 29) |
| `java_item_touch` (item pickup) | `native_item_picked_up` (API 40, cancel only; Clumps' take-part has none) |
| `note_java_death*` | `native_player_died` (API 40) |
| `local_java_command`, `guest_java_command`, `send_java_commands` | `native_register_commands` / `native_perform_command` (API 8): usage strings, not Brigadier trees; the Java tree is merged after the native one (hub `mod_commands`) |
| `java_player_chat` (chat.rs, lan.rs) | none (native has no server chat hook) |
| `java_select_slot`, `java_ride`, `java_orb_touch`, `java_crafted`, `java_tickets` (chunk tickets in streaming), precipitation | none |
| Screens (`Screen::JavaMod`), REI's overlay, HUD mods | `native_mod_screen*`/`native_screen_*` (API 19/20), `native_panel_*` (API 2), `native_hud`/`native_health_hud` (API 27): layouts of their own, not our drawn-op frames |
| Mod particles (`ParticleEngine::add_mod`) | upstream's `ParticleOptions::mod_particle` (native particle types): `add_mod` now uses its sprite sets |
| Mod mobs drawn (`java_entity_geometry`, shadows) | `native_mob_submit` (draws Horizon's own mobs only) |
| Creative tab pages, item components of content mods | upstream's own now (NeoForge-style pages, `component_patch`): ours dropped |

Upstream also moved the client's world, rendering and screens into `crates/horizon-client-render`
(`hud.rs`, `particles.rs`, `mesher.rs`, `streaming.rs`, `options_ui.rs`, `chunk.rs`, `biome.rs`,
`world.rs`): our changes there moved with them.

### Content preparation time (2026-11-01, bridge-worldgen-3)

A new set of jars builds its content (`horizon_jvm::content::build` → port-pack's inventory,
oracle, carry, then `mod_content::build`). With the 110-jar pack (2 cores) that was 173 s, and
~140 s of it was one Rust function on the main thread: `oracle::own_states`, which gave each
mod block state a vanilla shape by `bounds_distance` against **every** vanilla state (~28k) —
mods' ~40k states × 28k. Vanilla has only a few hundred distinct bounds pairs, so the search
now runs over those (`ShapeIndex::distinct`, least name kept per pair, the same tie-break) and
memoizes per bounds pair (`ShapeIndex::nearest`). 173 s → 107 s; the content mods it writes
are byte-identical (`diff -r` of two caches: only scratch paths, map order, and the Java
oracle's sampled drop counts differ, run to run). What's left, in order: the Java oracle
(class loading, ~65 s), `mod_content::tables` (~15 s), the facts JSON read (~10 s).
Sample it with gdb (`gdb -p $(pgrep -x horizon-client) -batch -ex bt`, every 2 s).

## Working on it

- **The task queue is [java-bridge-tasks.md](java-bridge-tasks.md)**: take your area's next task, follow its loop, add a report there.

- One task a branch (`bridge-<task>` off `java-bridge`); keep PR-sized steps.
- Commit as the user (`iaxivers`) with the Co-Authored-By line; never commit `.local/`.
- Write comments like the code around: what vanilla does and why the mirror does it.

- **GeckoLib and partial tick 1.0 (26.3 trap).** GeckoLib treats a partial tick of exactly 1.0
  as a paused game and freezes every animation. The bridge renders at 0.999
  (`HorizonEntityRender.PARTIAL_TICK`); never pass 1.0F to a mod's render state.

- **Ridden mobs are client-authoritative (26.3).** `Player.isClientAuthoritative` is true, so
  on a server `LivingEntity.travelRidden` zeroes a ridden mob's motion and waits for the
  rider's client. Mirrored players have no Java client: `RiderAuthorityMixin` makes them
  non-authoritative and the mirror moves the mob. Never call `ServerPlayer.rideTick` on a
  mirrored player: it runs `ServerPlayer.tick`.
