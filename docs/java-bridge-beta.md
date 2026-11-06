# Fabric mods in Horizon: testers' guide

This fork of [Horizon](https://github.com/horizon-rust/horizon) (Minecraft: Java Edition 26.3
rewritten in Rust) runs **real Fabric mods for 26.3** inside Horizon.

> **Beta: things will break.** Don't use a world you care about, and keep backups. This is an
> unofficial fork, not part of upstream Horizon, and not made by Mojang or Microsoft.

**You play it only through the Horizon launcher, signed in with your Microsoft account.** The
launcher checks that the account owns Minecraft: Java Edition before it starts the game, and
before it downloads anything for the mods (the Fabric mods build, Java 25). There is no offline
way to play the mods.

| System | Status |
|---|---|
| Windows 10/11 (64-bit) | The bridge works: tested on Windows 11 with an NVIDIA GPU (Windows support by [martipops](https://github.com/martipops)). The launcher's Java mods tab is untested there |
| Linux | Works: the fork's main test system (Fedora, Ubuntu) |
| macOS | No Fabric mods build yet |

## What you need

- **Minecraft: Java Edition**, on a Microsoft account that owns it. Horizon contains no Minecraft
  files: it makes what it needs from your own copy.
- **Minecraft 26.3 played once**, to the title screen, in the official launcher, so its files are on
  your computer.
- A graphics card that can run modern games, about **10 GB of free disk** to build from source, and
  **8 GB of RAM** (16 GB for a big modpack).

## Get it

Download the fork's launcher from the **[`java-mods` release](https://github.com/iaxivers/horizon-java-bridge/releases/tag/java-mods)**
and unpack it anywhere:

- **Windows:** `horizon-launcher-windows-x64.zip` → `horizon-launcher.exe`.
- **Linux:** `horizon-launcher-linux-x86_64.tar.gz` → `horizon-launcher` (`chmod +x` if needed).

It is Horizon's launcher with the Fabric switch, and it never updates itself (not into the main
launcher, which has no switch, and not into a newer fork launcher either): download it again from
the same page for a newer one. The game build with Fabric mods comes from that release too, by
itself, when you turn the switch on.

### From source instead

Install the tools:

- **Windows**, in PowerShell. When the Rust installer asks about Visual Studio, accept its option:
  ```powershell
  winget install Git.Git Rustlang.Rustup EclipseAdoptium.Temurin.25.JDK
  ```
  Close the PowerShell window and open a new one afterwards.
- **Fedora:** `sudo dnf install git gcc java-25-openjdk-devel alsa-lib-devel systemd-devel libxkbcommon-x11-devel`,
  then Rust from [rustup.rs](https://rustup.rs).
- **Ubuntu / Debian:** `sudo apt install git build-essential openjdk-25-jdk libasound2-dev libudev-dev libxkbcommon-x11-dev`,
  then Rust from [rustup.rs](https://rustup.rs).

Then (about 20 minutes):

```sh
git clone -b java-bridge https://github.com/iaxivers/horizon-java-bridge
cd horizon
cargo build --profile fast -p horizon-client --features horizon-client/java-mods
cd launcher && cargo build --release && cd ..
```

Start `launcher/target/release/horizon-launcher` with `HORIZON_LAUNCHER_NO_SELF_UPDATE=1` set, and
after step 2 below give the version your build:
`launcher/target/release/horizon-launcher java-mods use-build target/fast/horizon-client`.
After a `git pull`, build again and run `use-build` again.

## Set it up (once)

1. **Sign in:** in the launcher, add your Microsoft account (or `horizon-launcher account sign-in`).
2. **Install a Horizon version** on Home and let the launcher prepare it from your Minecraft 26.3.
3. **Turn on Fabric mods:** on Home, the **Fabric mods** switch next to **Play** (or Play's **⋯** menu)
   (or `horizon-launcher java-mods on`). A **Java mods** tab appears on Home. "reads prepared files
   of format N": the build and your Horizon version are too far apart; update the version (or
   wait for the next build).
4. **Add mods:** the Java mods tab > **Add**, or drop jars on it (or `horizon-launcher java-mods add <jar>`).
5. Press **Play**.

**The first start takes a few minutes.** The launcher downloads the Fabric mods build for your
version (and Mojang's Java 25 if you have no Java 25 JDK), with its progress on Play and its lines
in the game log; then the game builds the mods' content once. If a download fails, Play stops and
says why. Before each Play the launcher checks for a newer build and takes it when it fits your
launcher; a build that needs a newer launcher is left alone (yours keeps playing) and the game
log says so: download the launcher again from the release page to get it.

**Check it worked:** make a Creative world, type `/give @s ` and press Tab: your mods' items
should be in the list.

**To play without mods:** turn **Fabric 26.3 mods (beta)** off. Your jars stay in the Java mods
tab for next time.


## Add mods and play

Use **Fabric mods for Minecraft 26.3**, the same `.jar` files as normal Fabric, from
[Modrinth](https://modrinth.com/mods?g=categories:fabric&v=26.3) or CurseForge. Remember the
libraries a mod requires too; Modrinth lists them as dependencies. The Java mods tab marks a jar
made for another Minecraft version (it can't start) and two jars of the same mod (keep one).
A Fabric jar dropped anywhere on the launcher goes into the selected instance's Java mods.

- **The first start with a new set of mods takes a few minutes.** After that, usually under a
  minute.
- **Some jars are skipped, and the game says so:** Fabric API (Horizon brings its own, the
  version it's tested with, so yours is never used), and
  renderer replacements like Sodium and Iris, with any mod that requires them. Horizon has its own
  renderer. Your files aren't changed or deleted.
- **If your mods can't start, the game stops and names the problem,** instead of opening your world
  without them, which could lose their blocks and items. The Java mods tab marks the mod that
  failed. Usually one mod is the cause: remove it and try again.
- **If one mod fails while you're playing,** a `[Java mods]` chat message says so, and the game goes
  on without that part of it.

## What to test

Do what you can, in any order, and tell us what happened. Each test has what you should see.

| # | Test | You should see |
|---|---|---|
| 1 | Start the game with one content mod (Farmer's Delight is a good one) | The title screen, then its items in the Creative inventory |
| 2 | Place a mod's block and use it (a cooking pot on a stove) | The block looks right, its screen opens, and it works |
| 3 | Fill a mod's chest or machine, quit to the title screen, reopen the world, then restart the game and check again | Everything is where you left it |
| 4 | Start the game a second time with the same mods | A much faster start |
| 5 | Add a mod, remove a mod, and start again | The change takes effect, and the rest of the world is fine |
| 6 | Your favourite modpack | It starts: tell us which mods work and which don't |
| 7 | Play for an hour or two | It doesn't get slower or keep using more memory |
| 8 | Explore far enough to find mod biomes and structures | They appear |
| 9 | Esc > **Open to LAN**, and have a friend join (same build, same mods) | They join and can see and use mod blocks |
| 10 | The FPS (F3), compared with the same mods in normal Minecraft | Tell us both numbers |
| 11 | A user name or folder with spaces, accents or non-Latin letters in it | The same as any other start |
| 12 | Turn Fabric mods off and play | Plain Horizon, no Java started, no Java mods tab |

## Known problems

No need to report these, though tell us if you learn more about them:

- Some mod blocks are **invisible right after you place them**, until you restart the game; a few
  invisible blocks in swamps.
- **VoxelMap** fails beside Xaero's maps (both use the same network message). Use one map mod.
- Mods that change how the game looks, made for Minecraft's renderer, can fail in part: they show a
  `[Java mods]` chat message and the game goes on.
- Some missing textures (a purple and black block) here and there.

## Reporting a bug

Open an issue at [iaxivers/horizon-java-bridge](https://github.com/iaxivers/horizon-java-bridge) if issues are on, or tell
whoever gave you this guide. One bug per report, with:

1. **What happened, and what you expected**, and the steps to make it happen again if you know them.
2. **Your mods:** the list of jars, or the modpack's name.
3. **The log:** `logs/latest.log` in the game folder ([where](#where-things-are)). It's replaced at
   each start, so copy it before you start again.
4. **If it crashed:** the newest file in `crash-reports/` in the game folder, if there is one.
5. **For anything visual:** a screenshot (F2 saves one in `screenshots/`) and the coordinates (F3).
6. **Your system:** Windows or Linux and its version, and your graphics card.

**The game froze?** Before closing it, save where it's stuck. This needs a JDK, and uses the number
of the running `horizon-client`: from Task Manager's Details tab on Windows, or `pgrep -x horizon-client`
on Linux.

```sh
jcmd PROCESS_NUMBER Thread.print > java-threads.txt
```

**Is it the mod or Horizon?** If you can, try the same mod in normal Minecraft with Fabric. If it
breaks there too, it's the mod.

## Where things are

The Java mods tab's **Open folder** opens `mods-java/`, inside the instance's game folder. In the
game folder are `saves/` (worlds), `config/` (the mods' settings), `logs/latest.log`,
`crash-reports/` and `screenshots/`. `.horizon-cache/` is made for you: it's safe to delete, and
rebuilt on the next start.

## If something goes wrong

| It says | Do this |
|---|---|
| `Sign in with a Microsoft account that owns Minecraft: Java Edition` | Sign in (step 1). Nothing for the mods is downloaded before that |
| `… has no Fabric mods build here` | Do step 3 again (after an update of the Horizon version, too) |
| `… is not prepared yet` | Play 26.3 once to the title screen in the official launcher, close it, and prepare the version again |
| `Java 25 for Fabric mods: …` | Check your internet connection, or install a JDK 25 from [adoptium.net](https://adoptium.net) |
| `your mods couldn't start` | Remove the mod it names. Check your mods are for 26.3 and have their libraries |
| `… is made for Minecraft 1.21…, not 26.3` | Get that mod's file for 26.3 |
| `This Fabric mods build needs a newer launcher` | Download the launcher again from the [`java-mods` release](https://github.com/iaxivers/horizon-java-bridge/releases/tag/java-mods) |
| `Minecraft's own … is missing` | Play 26.3 once to the title screen in the official launcher, then prepare the version again on Home |

More about how it all works: [java-bridge.md](java-bridge.md).
