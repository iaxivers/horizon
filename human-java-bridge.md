# Play Fabric mods in Horizon: the simple version

Horizon is Minecraft rewritten in Rust. This fork runs real Fabric mods in it. Follow these steps in
order and don't skip any. You do parts 1 to 3 once.

**This is a beta, so things will break. Don't use a world you care about.**

You need a Microsoft account that owns Minecraft: Java Edition, about 10 GB of free disk, and about
20 minutes. These steps are for **Windows**. On Linux, see the
[full guide](docs/java-bridge-beta.md#get-horizon) for part 2. macOS is untested.

## Part 1: set up Prism Launcher

1. Install [Prism Launcher](https://prismlauncher.org) and sign in with your Microsoft account.
2. Click **Add Instance**. Pick Minecraft **26.3**, then **Fabric** as the mod loader, and click OK.
3. Select the instance and click **Edit**, then **Mods**, then **Download mods**. Add **Farmer's
   Delight** and accept anything it says it requires.
4. Press **Play**. When Minecraft reaches the title screen, close it.

## Part 2: install Horizon

1. Open the Start menu, type **PowerShell**, and press Enter.
2. Paste this and press Enter. Say yes to anything that asks, and wait for it to finish:
   ```powershell
   winget install Git.Git
   winget install Rustlang.Rustup
   winget install EclipseAdoptium.Temurin.25.JDK
   winget install Microsoft.VisualStudio.2022.BuildTools --override "--wait --passive --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
   ```
3. **Close PowerShell and open it again.** This step matters.
4. Paste this and press Enter. **It takes about 15 minutes**, so let it run:
   ```powershell
   cd $HOME
   git clone -b java-bridge https://github.com/iaxivers/horizon
   cd horizon
   cargo run --profile fast -p horizon-play
   ```
5. When the game opens, close it.
6. Paste this and press Enter. It prints a path: **copy that whole line.** You need it in part 3.
   ```powershell
   "$PWD\target\fast\horizon-play.exe"
   ```

## Part 3: connect Prism to Horizon

1. In Prism, **right-click your instance** and choose **Edit**.
2. Click **Settings** on the left.
3. Find **Custom commands** and **tick its checkbox**.
4. Paste the line you copied into the **Wrapper command** box. If it has a space in it, put quotes
   around it.
5. Close that window.

## Part 4: play

Press **Play** in Prism.

**The first start takes a few minutes**, and it may look like nothing is happening. That's normal.
Later starts are much faster.

**To check it worked:** make a Creative world, then type this in chat and press Enter:

```
/give @s farmersdelight:cooking_pot
```

If you get a cooking pot, you're done. Add any other Fabric mods for 26.3 in Prism, the same way as
normal.

## Good to know

- **To go back to normal Minecraft:** untick **Custom commands**.
- **Sodium, Iris and Fabric API are skipped on purpose.** Horizon has its own renderer and its own
  Fabric API. This is normal, and your files aren't touched.
- **If a mod fails while you're playing,** a `[Java mods]` message appears in chat and the game keeps
  going.
- **If the game says your mods couldn't start,** remove the mod it names and press Play again.
- **To update Horizon:** in PowerShell, run `cd $HOME\horizon`, then `git pull`, then
  `cargo run --profile fast -p horizon-play`.

## Something went wrong?

| What you see | What to do |
|---|---|
| `cargo` or `git` is not recognized | Close PowerShell, open it again, and retry. If it still fails, redo part 2, step 2 |
| `linker link.exe not found` | The Visual Studio line in part 2 didn't finish. Run it again, then reopen PowerShell |
| `Minecraft 26.3 isn't on this computer yet` | Do part 1, step 4 |
| The game opens, but it's normal Minecraft | Check part 3: the box must be ticked and the path must be the whole line |
| Anything else | Send the log: `horizon\logs\latest.log` inside the instance's `minecraft` folder. In Prism, click **Folder** to open it |

Want more detail, other ways to play, or a list of things to test? See the
[full testers' guide](docs/java-bridge-beta.md).
