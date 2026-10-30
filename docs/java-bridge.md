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
| Headless client | `HorizonClient.java`: an allocated `Minecraft` (options, font, gui, sound/text-input managers, game mode), a `LocalPlayer` per player, screens (`openScreen`, client menus, `frame`, `input`, `tickScreen`), `as(player, …)` swaps `minecraft.player` and `gui.screen` |
| Screen drawing | `HorizonGraphics.java` records `GuiGraphicsExtractor` calls as JSON ops (blit, sprite, fill, gradient, text, item, decorations, tooltips, pose, `typing`) |
| Mods' messages | `HorizonLoop.java`: server→client payloads queued and delivered after `syncMenus` (`flush`); client→server run at once. Mixins: `PlayNetworkingMixin` (server side), `ClientPlayNetworkingMixin` |
| Mixins | `java/platform/horizon-platform.mixins.json` (`client` list: headless-client only) |
| Mods' entities | `HorizonEntities.java` (live per level, ticked as `tickNonPassenger`, saved in `horizon-java/entities/`, attack/interact), `HorizonEntityRender.java` (the mod's renderer submits into a recording collector: part trees once, poses + matrix + texture per tick), mixins `ServerChunkCacheMixin` (a chunk source holding nothing), `PathNavigationRegionMixin`, `ItemModelResolverMixin`; Rust: `game/java_entities.rs` (draw, aim), `server_mods.rs` (`send_java_entities`, `java_entity_attack`/`interact`, `/summon`) |
| Content conversion | `crates/horizon-port-pack` (`oracle.rs`, `carry.rs`), `HorizonContent.java` (facts, creative tabs), `crates/horizon-client/src/mod_content.rs` (content root) |
| Game side (Rust) | `crates/horizon-client/src/game/java_mods.rs` (start, content, creative), `server_mods.rs` (actions from Java, menus, block entities, random ticks, `JAVA_STATES`), `java_screen.rs` (draws frames, sends input, key maps) |

## How a tick flows

1. Horizon's server tick → `server_mods.rs` → `events::game_time`, `events::server_tick` →
   `HorizonServer.tick`: players mirrored, Fabric events fired, mods' hooks, levels' ticks,
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

Next (top failure of the pack, in order):
1. Client-only screens and the client half of `Item.use`: Nature's Compass opens its screen in
   `use` when `level.isClientSide()`; the headless client never runs client-side item use, its
   players sit in server levels, nothing catches `Minecraft.setScreen`, and Horizon's window
   shows a mod screen only over a container menu. Needs: a client level (or a client-side
   flag) for the headless player, `setScreen` captured into `SCREENS`, and a menu-less screen
   mode in `java_screen.rs`. Same gap for config screens and guide books.
2. The headless client never ticks: no `ClientTickEvents`, no `Minecraft.runTick` (Xaero's
   lib finishes loading there: "Registry is not frozen yet!" at SERVER_STARTING).
3. Mod worldgen: TerraBlender/BOP biomes don't generate (Horizon's generator is vanilla's).
4. Mod chunk loaders: chunk tickets from Java are dropped.
Then: mod screens for LAN/remote players (frames go to the window's own player only), NeoForge
later.

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
