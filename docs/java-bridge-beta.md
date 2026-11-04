# Fabric mods in Horizon: testers' guide

**Just want to play? Use the [simple step-by-step version](../human-java-bridge.md).**

This fork of [Horizon](https://github.com/horizon-rust/horizon) (Minecraft: Java Edition 26.3
rewritten in Rust) runs **real Fabric mods for 26.3** inside Horizon.

> **Beta: things will break.** Don't use a world you care about, and keep backups. This is an
> unofficial fork, not part of upstream Horizon, and not made by Mojang or Microsoft.

| System | Status |
|---|---|
| Windows 10/11 (64-bit) | Works: tested on Windows 11 with an NVIDIA GPU |
| Linux | The bridge works: it's the fork's main test system (Fedora, Ubuntu). `horizon-play` is new there, so tell us how it goes |
| macOS | **Untested.** It may work. Tell us what happens |

## Quick start: press Play in Prism Launcher

The easiest way. Prism has a setting that says "run this program instead of Java". Point it at
`horizon-play`, and pressing Play starts Horizon instead of Minecraft, with that instance's mods and
your signed-in account. Prism keeps handling the mods as usual.

**You need:** [Prism Launcher](https://prismlauncher.org) with your Microsoft account signed in, and
Horizon ([get it](#get-horizon): you only do this once).

1. In Prism, make an instance for Minecraft **26.3** with **Fabric**, and add a mod. Farmer's Delight
   is a good first test.
2. **Right-click the instance** and choose **Edit**.
3. Click **Settings** on the left.
4. Find **Custom commands** and **tick its checkbox**.
5. In the **Wrapper command** box, paste the full path of `horizon-play`:
   - **Downloaded Horizon:** it's in the folder you extracted. Example:
     `C:\Users\you\Documents\horizon\horizon-play.exe`
   - **Built Horizon:** it's in `target/fast` inside the `horizon` folder. Example:
     `C:\Users\you\horizon\target\fast\horizon-play.exe`, or on Linux or macOS,
     `/home/you/horizon/target/fast/horizon-play`
   - If the path has a space in it, put quotes around it.
6. Close that window.
7. Press **Play**.

**The first start takes a few minutes**, and it may look like nothing is happening for a while. It
sets up the game files once, then builds the mods' content. Prism's log window shows lines starting
with `Horizon:`.

**Check it worked:** Horizon opens instead of normal Minecraft. Make a Creative world and type
`/give @s ` then press Tab: your mods' items should be in the list.

**To go back to normal Minecraft:** untick **Custom commands**.

## What you need

- **Minecraft: Java Edition**, on a Microsoft account that owns it. Horizon contains no Minecraft
  files: it makes what it needs from your own copy.
- **Minecraft 26.3 played once**, to the title screen, in the official launcher or Prism Launcher.
- A graphics card that can run modern games, about **10 GB of free disk** if you build from source,
  and **8 GB of RAM** (16 GB for a big modpack).

## Get Horizon

**If someone gave you a download** (`horizon-…-java.zip`): extract the whole folder somewhere you can
write to, like Documents. Don't run it from inside the zip. That's all: go to the
[quick start](#quick-start-press-play-in-prism-launcher).

**Otherwise, build it** (once; about 15 minutes). Install the tools, then run the same three lines
on every system:

- **Windows**, in PowerShell:
  ```powershell
  winget install Git.Git
  winget install Rustlang.Rustup
  winget install EclipseAdoptium.Temurin.25.JDK
  winget install Microsoft.VisualStudio.2022.BuildTools --override "--wait --passive --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
  ```
  Close the PowerShell window and open a new one afterwards.
- **Fedora:** `sudo dnf install git gcc java-25-openjdk-devel alsa-lib-devel systemd-devel libxkbcommon-x11-devel`,
  then Rust from [rustup.rs](https://rustup.rs).
- **Ubuntu / Debian:** `sudo apt install git build-essential openjdk-25-jdk libasound2-dev libudev-dev libxkbcommon-x11-dev`,
  then Rust from [rustup.rs](https://rustup.rs).
- **macOS (untested):** `xcode-select --install`, Rust from [rustup.rs](https://rustup.rs), and a
  JDK 25 from [adoptium.net](https://adoptium.net).

```sh
git clone -b java-bridge https://github.com/iaxivers/horizon
cd horizon
cargo run --profile fast -p horizon-play
```

That builds the game, prepares your Minecraft files and starts the game. Quit the game once it
opens, then go to the [quick start](#quick-start-press-play-in-prism-launcher).

To play again later, run the last line from the same folder. To update, run `git pull` first.

## Add mods and play

Both ways use **Fabric mods for Minecraft 26.3**, the same `.jar` files as normal Fabric, from
[Modrinth](https://modrinth.com/mods?g=categories:fabric&v=26.3) or CurseForge. Remember the
libraries a mod requires too; Modrinth lists them as dependencies.

**With Prism:** see the [quick start](#quick-start-press-play-in-prism-launcher).

### No Prism: drop jars in a folder

- **Download:** double-click `horizon-play`. It makes a `fabric-mods` folder beside itself: put your jars
  there and start it again.
- **Built from source:** run `cargo run --profile fast -p horizon-play`. It prints where the
  `fabric-mods` folder is (`.local/play/fabric-mods`): put your jars there and run it again.

You can also point it at a Prism instance, without changing the instance's settings:

```sh
cargo run --profile fast -p horizon-play -- --instance "PATH/TO/PrismLauncher/instances/MyPack"
```

Or, with a download: `horizon-play --instance "PATH/TO/PrismLauncher/instances/MyPack"`. This way
you play offline, because Prism isn't there to sign you in.

### What happens when you start

- **The first start with a new set of mods takes a few minutes.** After that, usually under a
  minute.
- **`horizon-play` skips some jars, and says so:** Fabric API (Horizon brings its own), and renderer
  replacements like Sodium and Iris, with any mod that requires them. Horizon has its own renderer.
  Your files aren't changed or deleted.
- **If your mods can't start, the game stops and names the problem,** instead of opening your world
  without them, which could lose their blocks and items. Usually one mod is the cause: remove it and
  try again.
- **If one mod fails while you're playing,** a `[Java mods]` chat message says so, and the game goes
  on without that part of it.

**Check the mods loaded:** type `/give @s ` and press Tab. The list should include your mods' items.

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
| 9 | Esc > **Open to LAN**, and have a friend join | They join and can see and use mod blocks |
| 10 | A dedicated server: see [below](#a-server-with-mods) | Players can join and use mods |
| 11 | The FPS (F3), compared with the same mods in normal Minecraft | Tell us both numbers |
| 12 | A user name or folder with spaces, accents or non-Latin letters in it | The same as any other start |

### A server with mods

Run the game once first, so it's prepared, then quit. Put the mods in the `fabric-mods` folder as above. In
the `game` folder (`.local/play/game` if you built from source), make a file called `eula.txt`
holding `eula=true`, if you agree to [Minecraft's EULA](https://aka.ms/MinecraftEULA). Then:

```sh
horizon-play -- --dedicated --nogui
```

From source, that's `cargo run --profile fast -p horizon-play -- -- --dedicated --nogui`. Players
join with this same Horizon build and the same mods.

## Known problems

No need to report these, though tell us if you learn more about them:

- Some mod blocks are **invisible right after you place them**, until you restart the game; a few
  invisible blocks in swamps.
- **Hoppers** don't work in packs with **Lithium**.
- **Litematica**'s screens and its in-world overlay don't show.
- **VoxelMap** fails beside Xaero's maps (both use the same network message). Use one map mod.
- Mods that change how the game looks, made for Minecraft's renderer, can fail in part: they show a
  `[Java mods]` chat message and the game goes on.
- Some missing textures (a purple and black block) here and there.
- In menus the game has no frame cap, so some GPUs whine: set **Max Framerate** in Video Settings to
  your monitor's refresh rate.

## Reporting a bug

Open an issue at [iaxivers/horizon](https://github.com/iaxivers/horizon) if issues are on, or tell
whoever gave you this guide. One bug per report, with:

1. **What happened, and what you expected**, and the steps to make it happen again if you know them.
2. **Your mods:** the list of jars, or the modpack's name.
3. **The log:** `logs/latest.log` in the game folder ([where](#where-things-are)). It's replaced at
   each start, so copy it before you start again.
4. **If it crashed:** the newest file in `crash-reports/` in the game folder, if there is one.
5. **For anything visual:** a screenshot (F2 saves one in `screenshots/`) and the coordinates (F3).
6. **Your system:** Windows, Linux or macOS and its version, and your graphics card.

**The game froze?** Before closing it, save where it's stuck. This needs a JDK, and uses the number
of the running `horizon-client`: from Task Manager's Details tab on Windows, or `pgrep -x horizon-client`
on Linux and macOS.

```sh
jcmd PROCESS_NUMBER Thread.print > java-threads.txt
```

**Is it the mod or Horizon?** If you can, try the same mod in normal Minecraft with Fabric. If it
breaks there too, it's the mod.

## Where things are

| What | With Prism or `--instance` | Download | Built from source |
|---|---|---|---|
| The game folder | `<instance>/minecraft/horizon` | `game` beside `horizon-play` | `.local/play/game` |
| The mods you add | `<instance>/minecraft/mods` | `fabric-mods` beside `horizon-play` | `.local/play/fabric-mods` |
| Mods' settings | `<instance>/minecraft/config` | `config` in the game folder | `config` in the game folder |

Inside the game folder are `saves/` (worlds), `logs/latest.log`, `crash-reports/` and `screenshots/`.
`mods-java/` and `.horizon-cache/` are made for you: they're safe to delete, and rebuilt on the next
start.

## If something goes wrong

| It says | Do this |
|---|---|
| `Minecraft 26.3 isn't on this computer yet` | Play 26.3 once to the title screen in the official launcher or Prism, close it, and try again |
| `Minecraft's files couldn't be prepared` | Do the same. In the official launcher, make sure the version is exactly 26.3 |
| `Java 25 couldn't be downloaded` | Check your internet connection, or install Java 25 from [adoptium.net](https://adoptium.net) |
| `Keep every file of the Horizon download together` | Extract the whole zip again, and run it from the extracted folder |
| `your mods couldn't start` | Remove the mod it names. Check your mods are for 26.3 and have their libraries |
| `couldn't run cargo` | Install Rust ([get Horizon](#get-horizon)), then open a new terminal window |
| Windows SmartScreen warns about the download | The builds aren't signed yet. Only run one from someone you trust: **More info > Run anyway** |

More about how it all works: [java-bridge.md](java-bridge.md).
