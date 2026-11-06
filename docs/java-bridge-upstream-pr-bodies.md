# Upstream PR bodies (ready, not opened)
Base: horizon-rust/horizon main 69fab597a6. Each branch is on the fork as `claude/up-<id>-<slug>`; open with e.g.
`gh pr create -R horizon-rust/horizon --head iaxivers:<branch> --base main --title "<title>" --body-file <section>`
(the fork is iaxivers/horizon-java-bridge; push the branch to a head the upstream repo can see first). Plan: before opening, file the tracking issue and `/claim` it, then replace `horizon-rust/horizon#10216` below with `#<tracking>; ... #10216` per the plan.
Common to all: clippy runs here with rustc 1.97, which flags a pre-existing `manual_range_contains` in upstream's `worldgen/aquifer/noise_based.rs` under `-D warnings`; no branch adds a clippy warning in the files it touches. `python3 tools/pr_ready.py` was not run (no `.local/` vanilla data in this environment).

---

## A1: Protocol: in-memory connection carries packets past 2 MiB

Branch: `claude/up-A1-wide-packets` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A1.

Useful without Java: a big content pack's join sends packets past the 21-bit frame (2 MiB) to the window's own client, and `frame::contents` refused them, so single player failed to join. An in-memory `Pipe` connection now frames with up to a 5-byte VarInt length (`frame::unbounded_contents`, `frame::wide_header`), as vanilla's LocalChannel has no frame limit at all. Sockets keep the 21-bit limit.

Depends on: –

Tests: `cargo test -p horizon-protocol --lib`: 209 passed (incl. `connection::wide::tests`: a 3 MB packet over a local pipe; a socket frame still refuses it). `cargo check --all-targets` clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A2: Big packs: stream gameplay-facts.json, trim the heap, HORIZON_MEM_LOG

Branch: `claude/up-A2-big-pack-memory` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A2.

Useful without Java: a modded pack's prepared tables cost gigabytes at load. `gameplay-facts.json` is streamed into rows holding only the fields the behaviour tables read (its serde_json tree took 10x its size: 3.4 GB for 208k states); `block-behaviours.json`'s tree is dropped after the facts and re-read only if the snapshot misses; `horizon_threads::trim_heap` (glibc `malloc_trim`) returns the parse's garbage after resources and worldgen data load; `HORIZON_MEM_LOG=1` logs resident memory per load stage (+ `texture_memory_probe`, `state_memory_probe` examples). Vanilla resources build the same tables.

Depends on: –

Tests: New `level::data::streaming::tests::streamed_rows_answer_as_the_json_tree` (GameplayRow vs the Value impl, full/sparse/extra-field rows, defaults). `cargo test -p horizon-world --lib level::data`: 6 passed; `-p horizon-threads`: 17 passed. `cargo check --all-targets` (threads, world, client-render, client) clean; no clippy warnings in touched files.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A3: Block models: smaller baked quads

Branch: `claude/up-A3-smaller-baked-quad` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A3.

Useful without Java: a baked block face kept its whole `ModelQuad` including the sprite name (a heap String) though its `layer` already names the sprite: 32 bytes + an allocation for each of a modded pack's 2.3 M faces. `BakedFace` now holds `BakedQuad` (no material) and `force_translucent`; Fusion finds a face's sheet by layer. The catalog logs how many models/faces it baked. Nothing drawn changes.

Depends on: –

Tests: `cargo test -p horizon-client-render --lib -- models breaking fusion mesher`: 77 passed, 19 ignored (need resources). `cargo check --all-targets` clean; no clippy warnings in touched files.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A4: Worldgen: data packs' structure templates

Branch: `claude/up-A4-pack-structure-templates` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A4.

Useful without Java: `data/<ns>/structure/*.nbt` in a data pack was never read, so packs replacing vanilla's pieces or adding jigsaw pieces generated the vanilla piece or nothing. As vanilla's ResourceManagerTemplateSource does, the top pack's template wins over the built-in one; `WorldgenOverrides` records which pack has each template, with a reader per pack (folder or zip).

Depends on: –

Tests: `overrides::pack_templates::tests` (top pack wins, no reader → not read). `cargo test -p horizon-world --lib -- worldgen::overrides worldgen::structure`: 62 passed; `-p horizon-client-render --lib datapack`: 9 passed. `cargo check --all-targets` clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A5: Worldgen: a noise can be seeded under another id

Branch: `claude/up-A5-noise-seed-id` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A5.

Useful without Java: some worldgen mods register a noise under one id but seed it from another's hash (Tectonic: `tectonic:parameter/<x>` uses `minecraft:<x>`). A noise JSON may carry a non-vanilla `horizon_seed` id that both the density graph and the generator's noise table hash instead of the name. Vanilla data never has the key.

Depends on: –

Tests: `density::graph::noise_seed::tests` (an aliased noise samples bit-identically to its seed's). `cargo test -p horizon-core --lib density`: 46 passed; `-p horizon-world --lib worldgen`: 119 passed. `cargo check --all-targets` clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A6: Content mods: blocks with their own states

Branch: `claude/up-A6-content-block-states` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A6.

Useful without Java: a content block could only take its template's states. A block file may now list its own `states` (properties, a vanilla state whose shapes it borrows or its own outline/collision/occlusion boxes, light, random ticks, default) or a compact `state_table` for tens of thousands of states, and a namespace listed in `namespaces` may declare blocks under its own name (BuildCraft's modules). This PR is the format and its checks; the game builds such blocks in A8 (content root, follow-up).

Depends on: –

Tests: `content::tests::state_table` (table ↔ states, checks), listed-namespace declaration test. `cargo test -p horizon-mods --lib`: 38 passed. `cargo check --all-targets` (horizon-mods, horizon-port-pack, cargo-horizon-mod) clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A7: Collision: content blocks follow their template's rule

Branch: `claude/up-A7-content-block-collision` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A7.

Useful without Java: a mod block state with neither a cached shape nor a contextual rule made the collision query fail (`Unsupported`); it now collides as a full cube (logged once). A collision export may also say which vanilla template each mod block copies (`templates`), and its states then take the template's rule (scaffolding, liquid, powder snow) with their own properties (none if those don't fit). Vanilla exports have no `templates`; the content root writes them in A8.

Depends on: –

Tests: `tests/mod_block_collision.rs`: 3 passed; `--lib collision`: 1 passed. `cargo check --all-targets -p horizon-world` clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A10: Models: OBJ map_Kd naming a texture slot

Branch: `claude/up-A10-obj-map-kd-slot` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A10.

Useful without Java: an OBJ material's `map_Kd 0` (bare name the model has a texture slot for, as Create's backtanks) now resolves through the slot instead of drawing `minecraft:0` (missing texture). Other bare names stay sprites.

Depends on: –

Tests: `bake::loader_tests::obj_slots`. `cargo test -p horizon-assets --lib bake`: 14 passed. `cargo check --all-targets` clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A11: Levels beyond vanilla's three (Dim::Extra)

Branch: `claude/up-A11-extra-levels` · Label: `feature`

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A11.

Useful without Java: joining a server with a mod's dimension (modded Fabric/Paper server) folded it into the Overworld (its chunks, shape, sky). `Dim::Extra(i)` indexes a process-wide registry of extra levels (`chunk::ExtraLevel`: id, dimension type, shape, coordinate scale, which vanilla level it is drawn as). A client registers each level the server names, with its dimension type; a server lists extra levels in `/execute in`, tick order and `loaded_dims`, scales portal travel by `DimensionType.coordinateScale`, and sends each extra level's dimension type. Draw-side checks use `Dim::vanilla()`. A world of vanilla's three registers none. Merged with upstream's per-level spans (`level_extra`).

Depends on: –

Tests: New `lan::data::tests::extra_levels_send_their_own_dimension_types` (`cargo test -p horizon-client-lib --lib lan::data`: 8 passed) and `game::remote::extra_level_tests` (Dim round-trip; compiles under `cargo check --all-targets`, **not run here**: the gameplay test binary was OOM-killed on this 7 GB machine, please run `cargo test -p horizon-client --bin horizon-client extra_level`). Suggest also `python3 tools/lan_e2e.py` and `tools/parity.py --changed origin/main`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A12: Replay viewer: --replay plays a ReplayMod recording (.mcpr)

Branch: `claude/up-A12-replay-viewer` · Label: `feature`

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A12.

Useful without Java: `--replay FILE` serves a ReplayMod recording over an in-memory pipe as a server would (its own login end, the recorded configuration, then play packets at their times) and the window joins as a spectator. What would act on the viewer is left out (recorder's health/death, screens, later teleports, game mode/abilities, disconnects, reconfiguration, transfers, Horizon's own `horizon:` payloads). Chat lines: `pause`, `play`, `speed <x>`, `skip <seconds>`; messages are translation keys (en_us).

Depends on: –

Tests: `lan::replay` tests (`cargo test -p horizon-client-lib --lib lan::replay`: 2 passed: parsing/phases/left-out packets, chat controls). `cargo check --all-targets` clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A13: Streaming: no proto over a loaded column; forced chunks first

Branch: `claude/up-A13-streaming-guards` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A13.

Useful without Java: (1) a proto finishing or being checked back in/saved for a column the world already holds no longer replaces or overwrites it (it undid blocks placed since); (2) a `/forceload`ed chunk and the work around it get the player's own priority (vanilla's FORCED ticket is level 31), so packs that forceload far away (Skyblock Infinite's island) aren't stuck behind the whole view; its neighbourhood decorates first. Also `Streaming::busy_parts`.

Depends on: –

Tests: New `streaming::tests::forced_chunks_are_wanted_as_soon_as_the_players`. `cargo test -p horizon-client-render --lib streaming`: 26 passed, 2 ignored. The loaded-column guard has no unit test: it needs a generation race with prepared resources (`tests/forced_chunks.rs`-style); suggest `python3 tools/parity.py --changed origin/main` (generation_saves).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## A14: Small fixes: ctl launch timeout, offscreen fps, /locate invalid, dropper into minecarts, more

Branch: `claude/up-A14-small-fixes` · Label: none (fix)

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR A14.

Useful without Java: `HORIZON_CTL_LAUNCH_TIMEOUT`; offscreen loading/menu frames paced at 60/s (vanilla's FramerateLimitTracker; with no vsync they took the whole GPU); no pause on lost focus while the level loads (held the server's chunks back); `/locate structure` with an unknown tag answers `commands.locate.structure.invalid`; a dropper fills a container entity in front (chest minecart, `getContainerAt`); `MountState::charge_jump` extracted; the server thread and the window's local connection log how they ended; horizon-ctl reports block biome, `may_fly`/`invulnerable`, passengers, `near_loading`; `examples/state_mesh_scan`.

Depends on: –

Tests: `cargo check --all-targets` (client-render, client) clean; no clippy warnings in touched files. Gameplay tests not run here (OOM building the gameplay test binary); please run `cargo test -p horizon-client --bin horizon-client` and `python3 tools/agent_control_e2e.py`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## B1: Level hooks for fluids and blocks another engine runs

Branch: `claude/up-B1-level-engine-hooks` · Label: `skip-release`

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR B1.

No behaviour change: the hooks answer none until the bridge (`java-mods`, off by default) is built and turned on; nothing in the plain build sets them. Adds default methods to `LevelAccess`: `foreign_fluid`/`foreign_fluid_changed` (a mod fluid carried on water's/lava's template flows by its own code), `liquid_interaction` (another engine takes `shouldSpreadLiquid`), `observe_neighbours_updated`; plus `Behaviours::precipitation_tick_with`.

Depends on: –

Tests: `level::tests::a_set_block_names_the_position_whose_neighbours_it_updates` (skips without prepared resources). `cargo test -p horizon-world --lib level::`: 108 passed. `cargo check --all-targets` clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## B2: Worldgen hooks: noise-only protos, unsupported structures, another generator's biomes

Branch: `claude/up-B2-worldgen-hooks` · Label: `skip-release`

```markdown
> 🤖 Opened by iaxivers's AI agent (Claude Code, Claude Opus 5.5), not by iaxivers personally.

Part of the Fabric mods bridge (design: docs/design/java-bridge.md, horizon-rust/horizon#10216): PR B2.

No behaviour change: the hooks answer none until the bridge (`java-mods`, off by default) is built and turned on; nothing in the plain build sets them. Adds `generate_noise`/`noise_chunk`/`noise_fill_of` (a chunk at doFill's end), `unsupported_structures` (logged once per dimension: a mod's own structure type), a `BiomeOracle` trait (`set_biome_oracle`, `expect_oracle`/`oracle_decided`, `spawn_origin`, `locate_structure`) and decorate leaving a region with another generator's neighbour. The fork's `vanillagen/java.rs` is renamed `vanillagen/foreign.rs`.

Depends on: –

Tests: `worldgen::structure::start::tests::unsupported`; `cargo test -p horizon-world --lib worldgen::structure::start`: 15 passed. `cargo check --all-targets` (world, client) clean.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
