# Java bridge: notes for the boss chat

The boss chat merges the worker chats' branches, keeps `java-bridge` green, plans the next tasks
and talks with the user. Read this, then [java-bridge.md](java-bridge.md) and
[java-bridge-tasks.md](java-bridge-tasks.md). Update this file when something here changes.

## The people

- **The user: iaxivers.** Owns the fork `iaxivers/horizon-java-bridge`. Fedora, RX 9070 XT, Ryzen 9600X,
  32 GB DDR5, Europe/Oslo. Often on mobile: short replies, plain words, casual is fine. Has
  Claude Max; mind usage but don't be stingy when it matters. Wants honest answers ("is this a
  tragedy?" gets a straight yes/no and why). Tests on his PC in the evenings.
- **Ludovic (ludovic111):** owns upstream `horizon-rust/horizon`. Decides what goes upstream and
  all releases (agents never add `release-now`). Upstream's docs say Fabric/NeoForge jars don't
  run; upstream is building its own "native mods" (Rust patches the launcher compiles). The
  bridge needs his buy-in before any upstream PR. Not contacted yet (2026-11-01).
- **ChinoCappuchino (NeuHorizon fork, PR #5053):** NeoForge 1.21.1 mods compiled to native code
  with Cranelift. As of 2026-10-31 his own docs say mod behaviour code doesn't run and mixins
  don't apply; his claims of "deep mixin support" weren't true then. Friendly; worth sharing a
  hook-table format and the Prism launch idea (we built a Fabric version: horizon-play). Don't
  merge his code into ours.

## The chats

Six workers, one area each (files in java-bridge-tasks.md): entities, worldgen, pack, client,
mixins, gpu. Plus, when needed, one-off chats (the upstream sync). Workers report by the user
pasting their final message here; patches come as attachments.

- The **client** chat often can't push (403): its work comes as `git am` patches. Apply with
  `git am -3`. If its patch series includes a commit you already merged (it builds on its own
  older branch), cherry-pick only the new commit.
- Workers often branch from their own previous, not yet merged branch. That's fine: merged
  commits are ancestors; only new work conflicts.

## Merging a round

1. `git checkout java-bridge && git pull --ff-only`, then `git checkout -B merge-batchN`.
2. Merge each pushed branch (`git merge --no-edit origin/bridge-…`), apply patches. Stop at each
   conflict and resolve it before the next merge.
3. Conflicts seen so far, and how they were settled:
   - `docs/java-bridge.md`, `regress.py`: keep both sides. In regress.py the joined `dict(`
     blocks need a `),` between them; check `python3 -m py_compile` and `--list`.
     Watch for two chats using the same MODS key or case name for different mods.
   - Two chats adding the same method (`players()`, `origin()`, `tickCount`/`currentTick`):
     keep one if they're the same, both if the names differ; fix callers' types (Rust
     `worldgen_origin` returns a struct; callers take `.x`, `.z`).
   - **Model loading (`HorizonClient.models`)** was rewritten by four chats. Settled design:
     pack3's async reload (prepared on background workers, applied on the client thread via
     `tasks()`, `finishModels()` each tick, given up after 2 minutes), plus client2's
     `ClientBootstrap.bootstrap()`, gpu2's colour-map listeners and `fonts(atlases, skins)`.
   - **Client join (`joined`)**: gpu's per-player version (`joined(ServerPlayer)`, own
     connection, login + spawn packets) plus client3's Fabric INIT/JOIN events at its end and
     the `dev.horizonlab.platform.` prefix filter.
   - `HorizonChunks`: entities' revision cache (`kept`) for server views + gpu's held client
     chunks (`held`); `Copy` has both `made` and `server`.
   - **Batch 4 (on top of the upstream sync):** upstream's design wins where both built the
     same thing: loot loading (shared tables, pack overlays, `native_loot_table_load`; pack-5's
     lazy loot was dropped and requeued), the parallel table loading in `load_resources` (pack-5's
     memory marks kept), `player_fall_damage` (mixins' routed falls go inside it). Branches made
     before the sync that touched `server_mods.rs` merge into `java_server.rs` by git's rename
     detection; check where a hunk landed when HEAD's side of a conflict is empty (it can split
     a doc comment from its item). `mem_log` lives in horizon-client-render.
   - Merge messages: write them with `git commit -F`, not `git merge -m … --no-edit` after a
     conflict (that leaves the `# Conflicts` lines). Never `filter-branch` this branch: it
     re-hashes upstream's signed commits.
4. Build Java (`java.sh`), then the game; `cargo fmt`; clippy (`-A
   clippy::manual_range_contains`: pre-existing in horizon-world).
5. `regress.py`. Don't edit `screen_test.sh` while a run is going (bash reads it as it runs).
   A failure right after a merge is often the test scene, not the code (the pot scene once
   failed because the player picked up seeds first: it now clears the inventory).
6. Update README's status table if something big changed; `check_public_tree.py`; commit as
   iaxivers; fast-forward `java-bridge`; push.
7. Give the user one short message per chat (or "continue with your queue"), and update
   java-bridge-tasks.md's queue.

## A fresh boss container (what batch 4 needed)

**Now one command:** `tools/java_bridge/setup_cloud.sh [modrinth-slug ...]` (JDK, Mojang files,
prepare, the build in the background, jars into `~/jars`). Workers use it too.

- JDK 25 from Adoptium into `~/jdk25` (`JAVA_HOME`, `HORIZON_JAVA_HOME`); `libasound2-dev
  libudev-dev`, `mesa-vulkan-drivers xvfb` for headless runs.
- Fabric's libraries: the build's own download fails on the proxy's certificate: fetch the
  `fabric-libs.tsv`/`fabric-api.tsv` files with curl into `.local/bridge/lib` (and
  `HORIZON_BRIDGE_LIBS`).
- Vanilla: a launcher-style folder from Mojang's servers (version JSON pinned in
  `research-lock.json`, client and server jars, libraries, assets), then `horizon-prepare
  --prepare --minecraft-dir <it> --server-jar <it>/server-26.3.jar` (~1 min). Never commit it.
- Test jars from Modrinth's API (`/v2/project/<slug>/version?game_versions=["26.3"]&loaders=
  ["fabric"]`). Slugs that differ from the names: theoneblock, gwg (Grid World),
  buildcraftrefabricated, trinkets-updated, voxelmap-updated, diorama-map.

## Test jars

Always in a merge round's run: the `server` case (dedicated server) and the LAN cases
(`maps-lan`, `rei-lan`, `lan_e2e.py --java-mods … --check javamount`). Batch 4 broke dedicated
servers and the headless set didn't see it.

Not in the repo. `regress.py --jars <folder>` picks jars by name (`MODS` in regress.py). A boss
chat's container starts empty: download the Modrinth jars the cases need (list in
java-bridge.md), or run only the fixture cases (keys, entity, attack, ride, slot, mixins2).
Some sandboxes refuse to run downloaded mod code: then say so; the user can run them.

## Upstream

Synced with upstream main 41d5649ed (2026-11-05, `bridge-upstream-sync-6`: 1539 commits;
conflicts in 25 files (11 of them launcher lang files), traps in java-bridge.md "Found in
upstream sync 6"). Before that 73d3ee3d2 (sync 5, 2452 commits). Before that
97937bcf8 (sync 4), when upstream removed WebAssembly code mods (our `server_mods.rs` is `game/java_server.rs` now) and
moved rendering into `crates/horizon-client-render`. Sync every day or two (a one-off chat, or
the boss when it's small); keep hooks in shared Horizon files to one-line calls into our files.

## Status (2026-11-02, merge-batch5)

Round 5 merged. New since batch 4: runs from a normal install (fetches Java 25 itself),
dedicated servers work again (batch 4 had broken them: always run the `server` case),
REI and JourneyMap settings per player, XaeroPlus and MiniHUD, riders seen sitting, horse-style
jumps, mod mobs hunting Horizon's mobs, mod mob drawing 42% cheaper, three hooks on upstream's
hook points, beacons/conduits/Warden/mace falls asking the mods, lazy loot again, the full
pack's world at 3.76 GB (was 5.2), Java's classes kept between runs.

Not yet: the user's PC run of the full pack; release builds and the launcher page; Windows;
Horizon's mobs reacting to mod mobs; far terrain with Tectonic.

## Chats and usage (from 2026-11-02)

- **A new chat for every round**, boss and workers alike. A long chat re-reads everything at
  each step, which is where most of the usage goes. Each new chat starts from the docs: the
  boss from this file, a worker from java-bridge-tasks.md (its area's queue and the Reports).
- The user is on Claude Max; at 67% of the week on 2026-11-02 the plan was 3 chats (sync, pack,
  client) and the others paused until the week resets. Ask the user before starting more.
- Waiting on builds and runs: one long command that waits (a foreground `timeout`, or a
  background run checked rarely), not a check every 5 minutes.
- Workers run only their cases; the full regress runs at merge time (the boss).
- The user's own PC runs cost no usage: prefer them for the big pack.

## State after merge-batch6 (2026-11-02)

- `java-bridge` = batch 6: upstream sync 3, Windows 2, round 6 of entities, pack, mixins, gpu,
  client. Worldgen's round 6 was still running (merge it alone when it reports).
- Conflicts this batch: `mounts.rs` (the Java mount's hearts inside upstream's
  `draw_mount_hud`), deaths (`java_player_died` only in `guest_die`: the window's
  `death_message` is gone upstream), `control.rs` (title text first, then a Java screen), two
  `maps-server` cases (gpu-8's kept; client-9's is `voxelmap-server`).
- Checked in the boss sandbox: clippy only (no Mojang data, 2 cores). The full regress goes to
  the user's PC; workers had run their own cases.
- Round 7 queue written; one new chat per area.

## State after merge-batch7 (2026-11-03)

- `java-bridge` = batch 7: upstream sync 4, round 7 of entities (`-9`), mixins (`-7-1`), gpu
  (`-9`), client (`-10`); pack's round 7 task 1 (`bridge-pack-8`) was already in. Worldgen's
  round 6 is still running: merge it alone when it reports.
- Found branches without pasted reports: `git branch -r --no-merged origin/java-bridge`,
  sorted by date; each branch's report is its diff to `docs/java-bridge-tasks.md`.
- Conflicts: only the docs (keep both) and `HorizonLoop.java` (gpu's `carried()` and client's
  `INBOX`/`pump()`: both kept, a `}` between them).
- This boss container (2 cores, 7 GB) ran the full setup: JDK 25 from apt
  (`openjdk-25-jdk-headless`), Mojang's files fetched by a script (version manifest → client,
  server, libraries, assets: ~5k files), `horizon-prepare` (73 s), Fabric's libraries fetched by
  the build itself (no proxy trouble this time), Modrinth jars by slug. First game build 19 min.
  Regress of 30 cases (round 7's, server, LAN, fixtures) took ~1 h 40 here.
- Results: see the batch 7 entry in the Reports. New bug queued (client 1): Java's log4j and
  Horizon both write a dedicated server's `logs/latest.log`.

## 2026-11-03: the first full-pack run on the user's PC

After batch 7 and worldgen 6, the boss chat debugged the user's run of the 81-jar test pack
(`tools/java_bridge/get_test_pack.py`) with him, straight on `java-bridge`: worldgen workers
racing on the structure ring positions, a Horizon-made chunk panicking beside Java ones, the
window's in-memory connection dropping a 2.3 MB packet, a pause during "Loading terrain". The
pack then played, also from the launcher (Java mods tab), at about Horizon's own speed. What's
broken is listed in java-bridge-tasks.md ("The user's PC run"). README's status rewritten.
Lessons: the game folder is the working directory (`mods-java` is read there, not from
`HORIZON_HOME`); `ctl.log` in the run's scratch has the game's stderr; a thread dump (`jcmd`,
`gdb -p $(pgrep -x horizon-client)`) settles a hang in one round. The user's runs are free and
fast: prefer them to cloud runs for big packs.

## Next big steps

1. Daily upstream syncs.
2. The user's PC results → fix what's really slow or broken.
3. Beta readiness: run from a normal install (bridge jars and a Java runtime shipped/fetched),
   Windows, clear in-game mod error messages, a known-working pack list. Then a closed beta
   (10–20 testers) — only after asking Ludovic.
4. Draft the message to Ludovic (what the bridge does, how it could fit: opt-in feature,
   separate crate, or on top of native mods).
5. NeoForge/Forge for 26.3 (same mirror; a different loader and events).
   Research done (2026-11-04, `bridge-neoforge-research`): FML starts in the bridge's JVM and
   loads mods (server half); the plan and first steps are in
   [java-bridge-neoforge.md](java-bridge-neoforge.md). Next: split the platform (step 1 there).
6. **1.12 (SkyFactory 4 and its packs): a committed goal, not a maybe** (the user, 2026-11-03:
   "it has to happen"; mods called too hard on day one run now). After NeoForge. The likely
   shape: Forge 1.12.2 mods on Cleanroom (a maintained 1.12.2 Forge on modern Java) in the
   bridge's JVM, against a 1.12 mirror of Horizon's 26.3 world: blocks and items translated
   both ways (pre-flattening ids and metadata ↔ 26.3 states), entities and NBT likewise, the
   1.12 client's GL rendering captured as today's GPU path does for 26.3. Start with a research
   round: what SkyFactory 4's mods touch (worldgen, tile entities, capabilities, GUIs), and
   a first 1.12 mod's block placed in Horizon. **Research done (2026-11-04):**
   [java-bridge-112.md](java-bridge-112.md) has the measured needs and eight phases; phase 1
   is `boot112`. Old-mod translation in general (remapping renames,
   a shim library, local only: many mods are all rights reserved) is the other route for
   1.20–1.21 mods.

## State after merge-batch8 (2026-11-04)

- `java-bridge` = batch 8: `bridge-gpu-10` (placed mod blocks no longer turn to air;
  `research-lock.json` accepts Mojang's republished 26.3 JSON `80608ff…`) and `bridge-mixins-8`
  (Horizon's hoppers into/out of Java mods' blocks via Fabric `ItemStorage`). Conflict: only
  java-bridge-tasks.md (kept both reports).
- Checked here (2 cores, 7 GB): `placed hoppers pot server rei-lan` pass. Setup: JDK 25 from apt,
  Mojang files by a manifest script into a launcher folder, `lab.py prepare` (accepted the new
  JSON thanks to gpu-10), `horizon-prepare` 44 s, game build 18 min, jars from Modrinth by slug
  (farmers-delight-refabricated, jade, appleskin, xaeros-minimap, rei, architectury-api,
  cloth-config, fabric-api). Five cases ~25 min.

## State after merge-batch9 (2026-11-04)

- `java-bridge` = batch 9: martipops' `windows/java-bridge` (an outside contributor's Windows
  work, run on Windows by him; reviewed before merging: `horizon-play` replaces `prism.sh`,
  downloads only Mojang files checked against `research-lock.json`; the game now exits with 78
  when the Java mods can't start, `HORIZON_JAVA_OPTIONAL=1` for the old way), `bridge-gpu-11`,
  `bridge-worldgen-7` (mods' structure templates), `bridge-client-11` (JEI). Conflicts: docs only.
- Checked here: `prism jei jei-lan templates placed hoppers pot server rei-lan` pass. The user
  says invisible blocks are fixed on his PC.
- Traps: `prism` needs `horizon-prepare --check` to pass (prepare with `--complete`, else the
  sounds are missing). `templates` timed out on 2 cores (1147 blocks away): its waits are longer now.

## Plan from 2026-11-04 (usage at 87%, resets Saturday 00:00)

Round 10 running (six chats: upstream sync 5, mixins-9, client-12, gpu-12, pack-9,
worldgen-8; their tasks are in java-bridge-tasks.md). When they report: one new boss chat
merges them all (merge-batch10). After that, nothing until Saturday: the user plays the pack
on his PC and collects testers' bugs. Saturday: a boss chat queues those bugs, then the
upstream prep chat (launcher switch, then the shared-file hooks), and the reply plan for Ludovic.

## State after merge-batch10 (2026-11-04)

- `java-bridge` = batch 10: upstream sync 5 (upstream main 73d3ee3d2), upstream-prep (the
  launcher's "Fabric 26.3 mods (beta)" switch: off by default, `--java-mods` /
  `HORIZON_JAVA_MODS=1` opt in), pack-9, mixins-9, client-12, worldgen-8, gpu-12 (Create draws
  and turns, mods' fluids, 3D Skin Layers).
- Conflicts: launcher strings and fields (both kept); `java_mods.rs` (the opt-in gate over
  client-12's `run_jars`); `start.rs` (both methods); gpu-12's OBJ loader dropped for upstream's
  (`RootAssets` reads the files); `HorizonClient` contextual bars (client-12's); two chats added
  regress cases `create` and `fluids`: the second ones are `create-client` and `fluids-flow`.
  Upstream's new glass home view needed the Java mods tab.
- Checked here: 23 cases pass (the round's 16 new ones, pot, placed, hoppers, jei, templates,
  server, rei-lan). Launcher `cargo check` clean.
- Ludovic's agent (2026-11-04): Horizon links to the bridge as an unofficial community project
  once (1) no offline play / no downloads before a Microsoft sign-in and (2) no code tied to other
  mods' internals. The user: launcher only, Prism and `horizon-play` go. Both queued first in
  java-bridge-tasks.md.

## State after merge-batch11 (2026-11-04)

- `java-bridge` = batch 11: Ludovic's two conditions done (launcher-only; generic-maps, -ui,
  -entities), plus survey-1, gpu-13, neoforge-research, 112-research, client-13, replay-1,
  mixins-10, voice-1, entities-10, trello-1, crash-1. Report and conflicts in
  java-bridge-tasks.md ("Merge batch 11").
- `regress.py` runs `nomodcode` first on every run (no mod's package in the bridge's code;
  `--code-check` alone, no game). A worker who needs a mod's class fails it: do it the vanilla
  way instead. Mod Menu's API is allowed (`ALLOWED_APIS`): ask Ludovic.
- Traps: `setup_cloud.sh` from an older checkout builds `horizon-play`, which is gone: merge
  first, then build. Two "keep both" merges that each emptied a list leave a dangling
  `Map.of(`: compile Java (`java.sh`) before the long build. A leftover headless game after a
  killed run eats 2.6 GB: the next build's rustc gets OOM-killed (`pgrep -x horizon-client`).
  Never `pkill -f` a pattern that is in your own command line.

## State after merge-batch12 (2026-11-05)

- `java-bridge` = batch 12: upstream sync 6 (upstream main 41d5649ed), upstream-prep-2 (bridge
  code out of Horizon's files: hooks listed in java-bridge-upstream.md), release-1 (the fork's
  signed `java-mods` release workflow; nothing published yet: the signing key secret isn't set),
  perf-1, generic-2, worldgen-9 (mods' dimensions), client-14, mixins-11, gpu-14, trello-2,
  112-boot, entities-10's TACZ follow-up. Report: java-bridge-tasks.md "Merge batch 12".
- **After upstream-prep-2, bridge code lives in `<file>/java.rs` child modules and
  `java_mods/{bridge,off}.rs`.** A worker branch made before it that edits the old place
  conflicts: re-apply its hunks where prep moved the code (`patch -F0` per hunk; fuzz
  misplaces them), and keep the plain build compiling (clippy without `java-mods` too). Game
  fields moved into `JavaGame` (`self.java.binds`, `self.java.overlay`...).
- **Two chats built the same thing** twice this round (client-14 with mixins-11, and with
  gpu-14): one owner per mechanism (click halves, mods' packets, client block entities) in
  the queue.
- Tests here are load-sensitive: `bettercombat`, `tacz`, `dimensions` failed in the 24-case
  run on timing; run a failure alone (or on the PC) before blaming a merge. Java-only bisect:
  put a commit's `crates/horizon-jvm/java` in place (remove the folder first: later files
  linger), `java.sh`, `regress.py --java-only`.
- Checked: game build, launcher build, clippy (both builds), 24 cases + voice.

## Overnight 2026-11-05/06 (boss log)


## Running (2026-11-06 ~00:30)
- Release run 42128315839 from java-bridge f90ab51 (Windows JVM `\\?\` fix). Check-in trigger at 01:02 Oslo.
- Gate agent: branch bridge-gate-2, works in /home/claude/horizon (don't touch that dir). Was sent the gate findings (a–e below).
- Bug-report button agent: branch bridge-bugreport, worktree /home/claude/wt-bugreport.
- Windows fixes agent: branch bridge-win-fixes, worktree /home/claude/wt-win (hunter items 1–7).
- My own worktree: /home/claude/wt-boss.

## First-run hunt findings: start a fix agent ONLY after win-fixes and bugreport are merged (same files: java_variant.rs, java_mods.rs, java_mods_page.rs, main.rs)
P1:
1. Play hides ensure() errors (java_mods_page.rs ensure_at_play → only log; main.rs ~1824) and shows the misleading "build isn't installed: turn mods off and on". Return the real error to the window (client_error).
3. Switch-on + Play at once → two ensure() downloads into the same .java.partial. Guard Play while java_mods.build is running, or a mutex in ensure.
4. Manifest needs a launcher contract/min version (java_release.rs fits): an old launcher must refuse a newer build with "download the new launcher" (tonight's failure 2). Plus a "newer fork launcher" notice.
5. Upstream version updates break Fabric: protect the version with a working java/ build from pruning (KEEP_NEWEST=2), manifest lists fitting upstream tags, hold back Update prompt when switch on and build doesn't fit.
P2:
6. Wrong-MC-version jars: read depends.minecraft on add/list, name the bad jar; failures_in should collect Fabric's "- Mod '…'" lines; warn on duplicate mod ids.
7. Player's own Fabric API jar replaces the pinned one (bridge.rs own_fabric_api): always use pinned, skip theirs with a note (as the guide says).
8. Game ignores launcher proxy (gate agent got this one too).
9. No progress/lines during first Play downloads (ensure_at_play passes |_|{} and eprintln!) → send Msg::Progress/Msg::Line.
10. Dev-command error messages + untranslated: java_variant.rs ~203, ~321, ~423 ("horizon-launcher java-mods …"), facade.rs "run horizon-prepare --prepare first", java_release.rs keys path. Make has_game_files also require libraries/client/*.jar.
11. Guide says Play updates the build; code doesn't (refresh_from_fork removal in gate-2 too). Decide: update at Play only when contract fits (item 4), else fix the guide.
P3: 12 HORIZON_JAVA_HOME trusted blindly / game prefers JAVA_HOME (jdk-26, JRE) — validate major+javac; 14 .jar dropped on window → route to java mods; flag jars in mods/; 15 resume downloads.

## Gate findings sent to gate agent
(a) refused gate opens world without mods → stop instead; (b) 429/5xx = NoAnswer; (c) failures_in prefix; (d) RECORD_LIFE 120 s vs wrappers; (e) proxy.

## Git
New remote branches only under `claude/` (others fail "fatal error in commit_refs"); existing branches (java-bridge, docs-java-bridge-design) push fine. Push URL https://github.com/iaxivers/horizon-java-bridge (worktree origin still says iaxivers/horizon).
Pushed: claude/boss-overnight (testers' guide: tonight's errors).
Also running: client-bugs diagnosis agent (keybinds, FOV, Xaero res, 3D skin, multiplayer), upstream PR plan agent. Both read-only, report to me.

## Done
- 00:35 Merged claude/bridge-bugreport into java-bridge (3990c9453b): Copy bug report + Open logs folder in Fabric mods tab, `horizon-launcher java-mods report`. Launcher tests + clippy pass; GUI click not screenshot-tested. NOT released (release 42128315839 builds f90ab51 without it). User test: GUI button on PC. Note: i18n every-language test already failing on java-bridge (ko etc. lack Fabric strings) — pre-existing.

- 00:40 Client-bugs brief committed to java-bridge: docs/java-bridge-brief-client-bugs.md (ee6b0ddd8d). Not implementable here: this container's prepared game can't compile the platform jar (HorizonBiomeMemo can't access Holder), so no in-game verification → these go to worker chats tomorrow (one per section).

- 00:45 claude/bridge-win-fixes (6acb08fd6a) DONE, NOT merged: game-side Windows start path changes need a real Windows run (martipops/testers). Unit tests + windows-gnu clippy for horizon-jvm pass. Real-Windows checks: non-ASCII user folder, 8.3-off drive error, UNC Java, replace build / remove jar while running, Foo.JAR.
- 00:45 Started first-run fix agent on claude/bridge-firstrun (based on win-fixes + java-bridge): items 1,3,4,6,7,9,10,11,14.

- 00:55 Upstream plan committed: docs/java-bridge-upstream-plan.md + tools/java_bridge/upstream_plan/ (5c5e6b508f). ~70 PRs; P0 (Rust split of java_server.rs/java_screen.rs + off stubs) and P1 (break the 37-class Java cycle) must happen on the fork first. P1 can't be compiled here (platform jar needs a prepared game) → worker chat on a real machine. P0 is Rust-only and compile-verifiable (feature on/off, HORIZON_BRIDGE_PLATFORM=source): candidate for a later check-in when no other build runs (disk). Decision for user: gate java_screen.rs in plain builds or not. gh has no access to horizon-rust/horizon (needs add_repo push) — opening PRs is the user's call anyway.

- 01:02 Release java-mods-2026-11-05-f90ab51 LIVE (all jobs success, 7 assets): Windows `\\?\` JVM fix. Testers with the 17d322a launcher get it at Play (fork refresh). Not in it: bug-report button (3990c94), docs.

- 01:05 Gate-2 DONE on claude/bridge-gate-2 (8531a4651e, on f90ab51), design doc on docs-java-bridge-design (4da817e3ae). Owner record = session file (UUID + Minecraft token, 0600, 10 min) passed by path in HORIZON_JAVA_MODS_OWNER; game verifies with Mojang /minecraft/profile + /entitlements itself. Dev opt-in only in `java-mods-dev`; prototypes behind `java-mods-prototypes`; refused gate + jars → game stops (exit 78); 429/5xx = no answer; proxy passed; warning dialog per instance; no auto-refresh, new `java-mods update`. Regress direct/forged_word/forged_record/owner PASS here on real 26.3 (stand-in services for owner). NOT tested: dialog in a window, real MS sign-in, Windows. NOT merged. Trade-off for user: Fabric mods need internet at every start. Merging requires bumping the manifest contract (first-run agent item 4) and a new launcher download for testers.

- 01:10 Disk at 95% → cleaned /tmp/hz-* leftovers + stale launcher incremental → 4.8 GB free. /home/claude/horizon HAS a working prepared 26.3 (gate agent ran real regress there) and is free now (gate agent done). NEXT when first-run agent finishes (disk): one worker on brief sections 1+2 (mod keybinds in Controls + mod FOV/zoom), working in /home/claude/horizon on a claude/ branch, regress + screenshots. Keep only one game build at a time; clean /tmp/hz-* after.

- 01:20 First-run fixes DONE on claude/bridge-firstrun (4a03f1bf50, messages with literal $'\n\n') and clean copy claude/bridge-firstrun-clean (7d1003afdd, same tree) — use the clean one. Based on win-fixes + java-bridge ee6b0dd. Contract=1; gate-2 merge must bump to 2. Not tested in a real window. Conflicts with gate-2 expected in java_variant.rs (refresh_from_fork), ensure_with, java_mods_page.rs.
- 01:20 Started keybinds+FOV worker in /home/claude/horizon on claude/bridge-modkeys-fov (real 26.3 regress + screenshots).
- MERGE ORDER for morning (all unmerged): win-fixes → firstrun-clean (contains win-fixes) → gate-2 (resolve conflicts, bump contract 2) → modkeys-fov. Then release + testers re-download launcher once.

- 01:45 INTEGRATION branch claude/bridge-integration (5bf5536df9) = java-bridge 5c5e6b5 + win-fixes + firstrun-clean + gate-2, conflicts resolved (gate-2's no-refresh ensure + `update`, firstrun's BUILD_LOCK/logging), CONTRACT = 2. Launcher clippy -D warnings clean, 115 launcher tests pass; horizon-client cargo check with java-mods and java-mods-dev OK; horizon-jvm 15 unit tests pass. (clippy on horizon-world fails on a pre-existing manual_range_contains lint from a newer clippy — not ours.) NOT run: regress on the integration (would need a second game target dir; disk ~5 GB and the keybind agent uses /home/claude/horizon/target/fast). Morning: user tests this ONE branch.

- 02:00 Translations: claude/bridge-integration → 7edae9f1ee, 2156 entries (26 keys × 86 languages); launcher i18n test passes. Small languages best-effort.

- 03:00 Keybinds+FOV DONE: claude/bridge-modkeys-fov (f6fb990275, off java-bridge). Mod keys (KeyMapping.ALL minus vanilla) listed/rebindable/saved in Controls, sent by mapping name; mod FOV via horizon:java_view applied in fov_degrees; `player.fov` in ctl state. New regress keys-rebind, keys-server, fov PASS; keys, pot, hud, litematica pass. Screenshots /home/claude/send/modfov-{zoomed,normal}.png, modkeys-*.png (checked: Xaero's waypoint keys in Controls). Zoomify itself untested (no jar). Not merged.
- 03:00 Freed disk (launcher debug cache deleted, 18 GB free). Started Xaero HUD-res worker: claude/bridge-hud-res in /home/claude/horizon.

## Rules tonight
Nothing to java-bridge or a release unless tested; everything else on branches; morning summary 08:00.

- 03:30 Check-in 2: started upstream A-series/B1/B2/B4a branch cutting (claude/up-*, not opened; bodies in /home/claude/send/upstream-pr-bodies.md).
- 05:30 Check-in 3: the session's server restarted ~03:40 and stopped both running agents (Xaero HUD-res, upstream A-series cutting); resumed both from their transcripts. Nothing merged in this check-in (nothing new tested).
- 06:45 Xaero HUD-res DONE: claude/bridge-hud-res (766129739a, on java-bridge 5c5e6b5). Per-player window (real GUI scale + framebuffer), fractional shrink, cap 2048 local / 512 network, big images own wgpu textures. World Map 427x240 → 2048x1152 at 2560x1440 (1280x720 at default), minimap 447x267 → 858x498. Regress nomodcode, maps-hires(new), xaeroplus, worldmap-lazy, maps-server, hud, keys, pot pass; clippy clean. Screenshots /home/claude/send/hudres-*.png (checked: sharp). Not merged. Started 3D Skin Layers worker (claude/bridge-skin-layers).
- 07:00 Upstream branches READY (not opened): 14 on the fork as claude/up-{A1,A2,A3,A4,A5,A6,A7,A10,A11,A12,A13,A14,B1,B2}-*, off upstream main 69fab597a6, each <500 lines, fmt + check + own-file clippy + unit tests (A11 Dim round-trip and A14 gameplay only compiled: OOM). PR bodies: docs/java-bridge-upstream-pr-bodies.md. Skipped: A8 (750, split), A9a/c/d (tangled), B4a (not a no-op: 18 MB atlas slots in plain build) → fork-side rework.
- 07:50 3D Skin Layers DONE: claude/bridge-skin-layers (6ed0e17472). HorizonSkins: offline skin via horizon:java_own_skin, account skins via textures property + skin-only downloader, profiles carry textures, Horizon hides its flat layers for mod-drawn parts. Regress nomodcode, skinlayers, skinlayers-skin(new), hud, keys, pot pass. Screenshots /home/claude/send/skinlayers-{before,after}*.png (checked: correct). Online account-skin path unexercised; hat part not detected; others don't see your offline skin.
- 08:20 claude/bridge-morning (5936019001) = integration + modkeys-fov + hud-res + skin-layers (docs conflicts only). Regress on it with JDK 25 (JAVA_HOME must be java-25 here; the container default is 21): nomodcode, direct, forged_word, owner, keys, keys-rebind, fov, maps-hires, skinlayers-skin, pot, hud ALL PASS.
