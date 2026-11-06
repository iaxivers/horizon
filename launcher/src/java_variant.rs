//! Fabric 26.3 mods (beta): the game build with the Java mod bridge (docs/java-bridge.md), per
//! instance. Off by default: an instance plays the plain build, has no Java mods tab and never
//! starts Java, even with jars in its `mods-java/`. On: the launcher installs the version's
//! Java mods build (`<asset>-java`: its release's own, else the fork's, [`java_release`]) in
//! the version's `java/` folder, beside the plain one, where it plays with the version's prepared files, and starts it with
//! [`OPT_IN_ARG`] and the Java 25 to run the mods on ([`JAVA_HOME_VAR`]).
//!
//! Nothing for the mods is downloaded before the owner gate: the build and Mojang's Java for the
//! version (`java-runtime-epsilon`) come only for a signed-in account that owns Minecraft: Java
//! Edition ([`owner_signed_in`]), and Play with the switch on never takes the developer option's
//! way past the gate (`dev_offline`). The game itself downloads no Java.
use crate::backend::{self, Release, CLIENT};
use crate::{account, java, java_release};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The folder in a version's folder holding its Java mods build.
pub const DIR: &str = "java";
/// What tells the game to run the Fabric mods (`game/java_mods.rs`, `opted_in`).
pub const OPT_IN_ARG: &str = "--java-mods";
/// The game's variable that turns Fabric mods on for developers' harnesses
/// (`horizon_jvm::gate::DEV_VAR`): never passed by the launcher.
const OPT_IN_VAR: &str = "HORIZON_JAVA_MODS";
/// What tells the game, beside [`OPT_IN_ARG`], that this command passed the owner gate
/// (`horizon_jvm::gate::OWNER_VAR`): without it the game runs no Fabric mods and downloads
/// nothing for them. Set only by [`switch_command`], which runs on Play's command after the gate.
pub const OWNER_VAR: &str = "HORIZON_JAVA_MODS_OWNER";
/// Written once the build is all there.
const COMPLETE: &str = ".installed";
/// The Java home the game runs the mods on (`horizon_jvm::JAVA_HOME_VAR`).
pub const JAVA_HOME_VAR: &str = "HORIZON_JAVA_HOME";
/// The Java the bridge needs (`horizon_jvm::JAVA_MAJOR`): 26.3's.
const JAVA_MAJOR: u32 = 25;
/// What Play and the switch say before a sign-in.
pub const SIGN_IN_FIRST: &str = "Sign in with a Microsoft account that owns Minecraft: Java \
     Edition first: Fabric 26.3 mods download nothing before that.";

/// The strict owner gate (never the developer option's offline pass), asked before any download
/// for the mods, as `backend::download_for_owner` asks it before downloading the game.
pub fn owner_signed_in() -> Result<(), String> {
    let mut accounts = account::Accounts::load();
    let pass = account::launch(
        &account::Endpoints::live(),
        account::client_id().as_deref(),
        account::store(),
        &mut accounts,
    );
    if let Err(e) = accounts.save() {
        eprintln!("Horizon: the accounts are not saved: {e}");
    }
    pass.map(|_| ())
        .map_err(|_| crate::i18n::tr(SIGN_IN_FIRST).to_string())
}

/// A Java 25 JDK here for the mods (the bridge builds its platform mod with `javac`):
/// `HORIZON_JAVA_HOME` when set, else Mojang's runtime the launcher downloaded, else one
/// installed on this computer.
pub fn java_home() -> Option<PathBuf> {
    if let Some(home) = std::env::var_os(JAVA_HOME_VAR).filter(|h| !h.is_empty()) {
        return Some(PathBuf::from(home));
    }
    let javac = if cfg!(windows) { "javac.exe" } else { "javac" };
    java::mojang_runtimes(&java::runtimes_dir())
        .into_iter()
        .chain(java::installed())
        .find(|r| r.major == JAVA_MAJOR && r.home.join("bin").join(javac).is_file())
        .map(|r| r.home)
}

/// [`java_home`], else Mojang's Java 25 downloaded now, its lines to `log`. The caller has
/// passed the owner gate.
fn ensure_java(log: &dyn Fn(&str)) -> Result<PathBuf, String> {
    if let Some(home) = java_home() {
        return Ok(home);
    }
    let need = java::Need::named(&JAVA_MAJOR.to_string())?;
    log(crate::i18n::tr("Downloading Java 25 for Fabric mods…"));
    java::install(&need, &|line| log(&line))
        .map(|r| r.home)
        .map_err(|e| format!("Java {JAVA_MAJOR} for Fabric mods: {e}"))
}

/// game-build.yml's Java mods builds: (`std::env::consts::OS`, `ARCH`, the asset). None for macOS yet.
pub const ASSETS: [(&str, &str, &str); 2] = [
    ("linux", "x86_64", "horizon-linux-x86_64-java.tar.gz"),
    ("windows", "x86_64", "horizon-windows-x64-java.zip"),
];

/// This system's Java mods build, if Horizon makes one.
pub fn asset() -> Option<&'static str> {
    asset_for(std::env::consts::OS, std::env::consts::ARCH)
}

fn asset_for(os: &str, arch: &str) -> Option<&'static str> {
    ASSETS
        .iter()
        .find(|(o, a, _)| *o == os && *a == arch)
        .map(|(_, _, asset)| *asset)
}

/// Whether the switch can be offered here.
pub fn available() -> bool {
    asset().is_some()
}

/// Installed version `tag`'s Java mods build folder.
pub fn dir(tag: &str) -> PathBuf {
    backend::version_dir(tag).join(DIR)
}

/// Whether installed version `tag` has its Java mods build. An imported build (a game of your
/// own, `build_import`) is its own: built with Java mods, it plays them with [`OPT_IN_ARG`].
pub fn is_installed(tag: &str) -> bool {
    complete(&dir(tag)) || crate::build_import::is_imported(tag)
}

fn complete(dir: &Path) -> bool {
    dir.join(COMPLETE).is_file() && dir.join(CLIENT).is_file()
}

/// Installs `release`'s Java mods build as installed version `tag`'s, checked against the
/// release's signed checksums as the plain build is.
pub fn install(release: &Release, tag: &str, progress: impl Fn(f32)) -> Result<(), String> {
    let asset = asset().ok_or_else(no_build_here)?;
    if !backend::version_dir(tag).join(CLIENT).is_file() {
        return Err(format!("{tag} is not installed."));
    }
    let published = release.nightly.as_deref().unwrap_or(&release.tag);
    let sha256 = backend::release_digest(release, asset).map_err(|e| missing(tag, &e))?;
    let url = crate::signed::asset_url(published, asset);
    download_into(tag, &url, &sha256, "", &progress)
}

/// Installs the java-bridge fork's own Fabric mods build ([`java_release`]) as installed
/// version `tag`'s: signed with the fork's key, and only when `tag` prepares the files it reads.
pub fn install_from_fork(tag: &str, progress: impl Fn(f32)) -> Result<(), String> {
    let asset = asset().ok_or_else(no_build_here)?;
    let version = backend::version_dir(tag);
    if !version.join(CLIENT).is_file() {
        return Err(format!("{tag} is not installed."));
    }
    let keys = java_release::keys();
    let fetch = |url: &str| backend::fetch_small(url);
    let manifest = java_release::manifest(&keys, &fetch)?;
    java_release::fits(&manifest, java_release::prepared_format(&version), tag)?;
    let sha256 = java_release::digest(asset, &keys, &fetch)?;
    let url = java_release::asset_url(asset);
    download_into(
        tag,
        &url,
        &sha256,
        &java_release::marker(&manifest),
        &progress,
    )
}

/// The fork's commit installed as `tag`'s Fabric mods build, when it came from the fork's release.
fn fork_commit(tag: &str) -> Option<String> {
    let text = fs::read_to_string(dir(tag).join(COMPLETE)).ok()?;
    java_release::marked_commit(&text).map(str::to_owned)
}

/// Downloads `url` (`sha256`), unpacks it and makes it `tag`'s Java mods build.
fn download_into(
    tag: &str,
    url: &str,
    sha256: &str,
    marker: &str,
    progress: &impl Fn(f32),
) -> Result<(), String> {
    let version = backend::version_dir(tag);
    let partial = version.join(".java.partial");
    let _ = fs::remove_dir_all(&partial);
    fs::create_dir_all(&partial).map_err(|e| e.to_string())?;
    let archive = partial.join("download");
    backend::download(url, 0, sha256, &archive, progress).map_err(|e| missing(tag, &e))?;
    let unpacked = partial.join("unpacked");
    backend::unpack(&archive, url, &unpacked)?;
    // One top folder (horizon-linux-x86_64-java/...), as the plain build's archive.
    let entries: Vec<_> = fs::read_dir(&unpacked)
        .map_err(|e| e.to_string())?
        .flatten()
        .collect();
    let top = match entries.as_slice() {
        [only] if only.path().is_dir() => only.path(),
        _ => unpacked.clone(),
    };
    place(&top, &dir(tag), marker)?;
    let _ = fs::remove_dir_all(&partial);
    progress(1.0);
    Ok(())
}

/// A 404 or an asset the signed checksums don't list: the release has no Java mods build.
fn missing(tag: &str, why: &str) -> String {
    if why.ends_with("answered 404") || why.contains(asset().unwrap_or("-java")) {
        crate::i18n::trf(
            "its release has no Fabric mods build to download yet ({why}). Pick another \
             Horizon version for {tag}'s instance on Home, or wait for the next build.",
            &[("why", why), ("tag", tag)],
        )
    } else {
        why.to_string()
    }
}

fn no_build_here() -> String {
    "Fabric mods have no build for this system yet (Linux and Windows only).".into()
}

/// Moves an unpacked build into `to`, replacing what was there; `marker` goes in its install
/// marker (empty, or the fork release's commit: `java_release::marker`).
fn place(from: &Path, to: &Path, marker: &str) -> Result<(), String> {
    if !from.join(CLIENT).is_file() {
        return Err(format!("The download has no {CLIENT}"));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(from.join(CLIENT), fs::Permissions::from_mode(0o755));
    }
    fs::write(from.join(COMPLETE), marker).map_err(|e| e.to_string())?;
    swap(from, to, &|a, b| fs::rename(a, b))
}

/// What replacing the build says while the game runs from it (Windows locks a running program's
/// folder).
pub const CLOSE_GAME_FIRST: &str =
    "Close the game first: it is running from the Fabric mods build this replaces.";

/// Moves `from` in as `to` without ever leaving `to` half there: the old build is moved aside
/// first (refused whole while the game runs from it), the new one moved in (the old one moved
/// back if that fails), and only then is the old one deleted. `rename` is `fs::rename`.
fn swap(
    from: &Path,
    to: &Path,
    rename: &dyn Fn(&Path, &Path) -> std::io::Result<()>,
) -> Result<(), String> {
    let name = to
        .file_name()
        .map(|n| n.to_string_lossy())
        .unwrap_or_default();
    let aside = to.with_file_name(format!(".{name}.old"));
    // A crash between the moves leaves it; it is never the build in use.
    let _ = fs::remove_dir_all(&aside);
    let had = to.exists();
    if had {
        rename(to, &aside).map_err(|e| {
            if in_use(&e) {
                crate::i18n::tr(CLOSE_GAME_FIRST).to_string()
            } else {
                format!("{}: {e}", to.display())
            }
        })?;
    }
    if let Err(e) = rename(from, to) {
        if had {
            let _ = rename(&aside, to);
        }
        return Err(format!("{}: {e}", to.display()));
    }
    let _ = fs::remove_dir_all(&aside);
    Ok(())
}

/// Whether moving or deleting a file failed because a program (the game) holds it: Windows
/// answers "access denied" or a sharing violation (32) or lock violation (33).
pub(crate) fn in_use(e: &std::io::Error) -> bool {
    e.kind() == std::io::ErrorKind::PermissionDenied
        || (cfg!(windows) && matches!(e.raw_os_error(), Some(32 | 33)))
}

/// A Java mods build the player has (built from a checkout with `--features java-mods`, or from
/// a fork's Actions) as installed version `tag`'s. Nothing signed it.
pub fn use_build(tag: &str, program: &Path) -> Result<(), String> {
    let _one = one_at_a_time();
    if !program.is_file() {
        return Err(format!("{}: no such file", program.display()));
    }
    if !backend::version_dir(tag).join(CLIENT).is_file() {
        return Err(format!("{tag} is not installed."));
    }
    let staging = backend::version_dir(tag).join(".java.partial");
    let _ = fs::remove_dir_all(&staging);
    fs::create_dir_all(&staging).map_err(|e| e.to_string())?;
    fs::copy(program, staging.join(CLIENT)).map_err(|e| format!("{}: {e}", program.display()))?;
    place(&staging, &dir(tag), "")
}

/// What keeps two downloads of a build (the switch turning on, Play, an install's
/// [`follow`], `use-build`) from writing the same `.java.partial` at once: each waits for the
/// one before it, then finds the build there.
static BUILD_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// [`BUILD_LOCK`], held until the guard drops; a panic in the holder doesn't keep it shut.
fn one_at_a_time() -> std::sync::MutexGuard<'static, ()> {
    BUILD_LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

/// Installs `tag`'s Java mods build unless it has it (the release of that tag, from GitHub), and
/// Java 25 unless there is one: only past the owner gate, when something is missing.
pub fn ensure(tag: &str, progress: impl Fn(f32)) -> Result<(), String> {
    ensure_with(tag, &progress, &|line| eprintln!("{line}"))
}

/// [`ensure`], saying what it does in `log` (the window's game log) as it goes.
pub fn ensure_with(tag: &str, progress: &dyn Fn(f32), log: &dyn Fn(&str)) -> Result<(), String> {
    let _one = one_at_a_time();
    let fork = fork_commit(tag);
    if is_installed(tag) && java_home().is_some() && fork.is_none() && has_game_files(tag) {
        return Ok(());
    }
    owner_signed_in()?;
    ensure_game_files(tag, log)?;
    if let Some(installed) = fork {
        refresh_from_fork(tag, &installed, progress, log);
    }
    if !is_installed(tag) {
        log(&crate::i18n::trf(
            "Downloading the Fabric mods build for {tag}…",
            &[("tag", tag)],
        ));
        fetch(tag, progress).map_err(|e| {
            crate::i18n::trf(
                "Fabric 26.3 mods are on, but {tag} has no Fabric mods build here: {why}",
                &[("tag", tag), ("why", &e)],
            )
        })?;
    }
    ensure_java(log).map(|_| ())
}

/// The version's own checked copy of the game, `.local/minecraft/<version>/jars/client.jar`, which
/// Fabric runs as its game jar (with the game's libraries beside it). The plain game reads only
/// the prepared resources, so a version whose resources were copied from another installed
/// version (`backend::reuse_prepared`) has none.
fn game_jar(tag: &str) -> PathBuf {
    backend::version_dir(tag)
        .join(".local/minecraft")
        .join(backend::game_version(tag))
        .join("jars/client.jar")
}

/// Fabric's game jar ([`game_jar`]) and at least one of the game's libraries beside it
/// (`libraries/client/`, which `horizon_jvm::facade::game_libraries` reads): without them the
/// game can't start the mods, so the launcher repairs them before Play.
fn has_game_files(tag: &str) -> bool {
    let jar = game_jar(tag);
    jar.is_file()
        && jar
            .parent()
            .and_then(Path::parent)
            .is_some_and(has_libraries)
}

/// Whether `<minecraft>/libraries/client/` (searched through) holds a `.jar`.
fn has_libraries(minecraft: &Path) -> bool {
    let mut stack = vec![minecraft.join("libraries/client")];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = fs::read_dir(&dir) else {
            continue;
        };
        for path in entries.flatten().map(|e| e.path()) {
            if path.is_dir() {
                stack.push(path);
            } else if path.extension().is_some_and(|e| e == "jar") {
                return true;
            }
        }
    }
    false
}

/// [`game_jar`] and the libraries, past the owner gate: copied, checked, from an installed
/// version that has them, else imported as Prepare imports them (the player's Minecraft
/// folder, or Mojang's download for the owner).
fn ensure_game_files(tag: &str, say: &dyn Fn(&str)) -> Result<(), String> {
    if has_game_files(tag) {
        return Ok(());
    }
    say(&crate::i18n::trf(
        "Getting Minecraft's own files for Fabric mods into {tag}…",
        &[("tag", tag)],
    ));
    let dir = backend::version_dir(tag);
    if horizon_prepare::artifacts::reuse_imported(&dir, say).unwrap_or(false) && has_game_files(tag)
    {
        return Ok(());
    }
    let log = |line: String| say(&line);
    let minecraft = backend::minecraft_dir(None)?;
    let (preparer, _) = backend::preparer(tag, &minecraft, backend::MinecraftFrom::Install, &log)?;
    match preparer {
        backend::Preparer::Process(mut cmd) => {
            let status = cmd.status().map_err(|e| format!("Preparing {tag}: {e}"))?;
            if !status.success() {
                return Err(format!("Preparing {tag} for Fabric mods failed ({status})"));
            }
        }
        backend::Preparer::InProcess(options) => backend::prepare_in_process(&options, &log)?,
    }
    if has_game_files(tag) {
        Ok(())
    } else {
        Err(crate::i18n::trf(
            "Fabric 26.3 mods need Minecraft's own files ({path}), and preparing {tag} didn't \
             make them: play Minecraft 26.3 once to the title screen in the official launcher, \
             then prepare {tag} again on Home.",
            &[("path", &game_jar(tag).display().to_string()), ("tag", tag)],
        ))
    }
}

fn fetch(tag: &str, progress: &dyn Fn(f32)) -> Result<(), String> {
    asset().ok_or_else(no_build_here)?;
    if crate::build_import::is_imported(tag) {
        return Err(crate::i18n::trf(
            "{tag} is an imported build, which has no Fabric mods build to download: import \
             one built with Fabric mods, or pick a Horizon release on Home.",
            &[("tag", tag)],
        ));
    }
    // The version's own release first; the fork's build when it has none (java_release).
    let own = backend::releases().and_then(|releases| {
        let release = releases
            .iter()
            .find(|r| r.tag == tag)
            .ok_or_else(|| format!("No Horizon release is tagged {tag}."))?;
        install(release, tag, progress)
    });
    own.or_else(|own| {
        install_from_fork(tag, progress)
            .map_err(|fork| format!("{own} From {}: {fork}", java_release::REPO))
    })
}

/// A build from the fork's release, replaced by a newer one that fits `tag` and this launcher
/// ([`java_release::fits`]). Best effort: the installed build plays when the check or the
/// download fails, and `log` says why.
fn refresh_from_fork(tag: &str, installed: &str, progress: &dyn Fn(f32), log: &dyn Fn(&str)) {
    let keys = java_release::keys();
    let fetch = |url: &str| backend::fetch_small(url);
    let newer = java_release::manifest(&keys, &fetch).and_then(|manifest| {
        let version = backend::version_dir(tag);
        java_release::fits(&manifest, java_release::prepared_format(&version), tag)?;
        Ok(manifest.commit != installed)
    });
    let not_updated = |e: &str| {
        log(&crate::i18n::trf(
            "The Fabric mods build of {tag} was not updated (the one installed plays): {why}",
            &[("tag", tag), ("why", e)],
        ))
    };
    match newer {
        Ok(true) => {
            log(&crate::i18n::trf(
                "Downloading a newer Fabric mods build for {tag}…",
                &[("tag", tag)],
            ));
            if let Err(e) = install_from_fork(tag, progress) {
                not_updated(&e);
            }
        }
        Ok(false) => {}
        Err(e) => not_updated(&e),
    }
}

/// After `release` installed as `tag`: its Java mods build too, when an instance with the switch
/// on plays that version (pinned to it, or following the launcher's). Best effort: Play tries
/// again.
pub fn follow(release: &Release, tag: &str) {
    let wanted = crate::instances::list()
        .iter()
        .any(|i| i.java_mods && i.version.as_deref().is_none_or(|v| v == tag));
    if !wanted || asset().is_none() || is_installed(tag) {
        return;
    }
    let _one = one_at_a_time();
    if is_installed(tag) {
        return;
    }
    if let Err(e) = owner_signed_in() {
        eprintln!("Fabric mods build of {tag} not installed: {e}");
        return;
    }
    if let Err(e) = install(release, tag, |_| {}).or_else(|own| {
        install_from_fork(tag, |_| {})
            .map_err(|fork| format!("{own} From {}: {fork}", java_release::REPO))
    }) {
        eprintln!("Fabric mods build of {tag} not installed: {e}");
    }
}

/// [`ensure`] for an instance with Fabric 26.3 mods on (Play from the command line or MCP); nothing
/// for one with them off.
pub fn ensure_for(
    instance: &crate::instances::Instance,
    tag: &str,
    progress: impl Fn(f32),
) -> Result<(), String> {
    if instance.java_mods {
        ensure(tag, progress)?;
    }
    Ok(())
}

/// `instances::apply`'s hook: with Fabric 26.3 mods on, the version's Java mods build, told to
/// run them ([`switch_command`]); the plain game's command otherwise.
pub fn apply(cmd: &mut Command, instance: &crate::instances::Instance) -> Result<(), String> {
    // Fabric 26.3 mods on: the version's Java mods build, told to run them.
    if instance.java_mods {
        switch_command(cmd)?;
    } else {
        // Off means off: a `HORIZON_JAVA_MODS=1` the launcher itself was started with (a
        // developer's shell) would turn them on in the game it starts.
        cmd.env_remove(OPT_IN_VAR).env_remove(OWNER_VAR);
    }
    Ok(())
}

/// Turns the plain game's command (`backend::client_command`) into the Java mods build's: the
/// same arguments, folder and variables, with [`OPT_IN_ARG`].
pub fn switch_command(cmd: &mut Command) -> Result<(), String> {
    let plain = PathBuf::from(cmd.get_program());
    let version = plain
        .parent()
        .ok_or_else(|| format!("{}: no version folder", plain.display()))?;
    asset().ok_or_else(no_build_here)?;
    let java = version.join(DIR);
    let tag = version.file_name().unwrap_or_default().to_string_lossy();
    let program = if complete(&java) {
        java.join(CLIENT)
    } else if crate::build_import::is_imported(&tag) {
        // An imported build plays the mods itself (`use-build` can still give it another).
        plain.clone()
    } else {
        return Err(crate::i18n::trf(
            "{tag}'s Fabric mods build isn't installed: press Play again to download it.",
            &[("tag", &tag)],
        ));
    };
    let mut next = Command::new(program);
    next.args(cmd.get_args());
    for (key, value) in cmd.get_envs() {
        match value {
            Some(value) => next.env(key, value),
            None => next.env_remove(key),
        };
    }
    if let Some(folder) = cmd.get_current_dir() {
        next.current_dir(folder);
    }
    // The Java the launcher has for the mods ([`ensure`] got it past the owner gate).
    if !cmd.get_envs().any(|(k, _)| k == JAVA_HOME_VAR) {
        if let Some(home) = java_home() {
            next.env(JAVA_HOME_VAR, home);
        }
    }
    // Play builds this command only past the owner gate (`client_as`, the window's session,
    // MCP's `play`): the game runs the mods only with this beside the argument. A developer's
    // `HORIZON_JAVA_MODS=1` in the launcher's own environment doesn't reach it.
    next.env_remove(OPT_IN_VAR).env(OWNER_VAR, "1");
    next.arg(OPT_IN_ARG);
    *cmd = next;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_java_builds_are_the_plain_ones_with_java() {
        for (os, arch, asset) in ASSETS {
            let plain = backend::PLATFORMS
                .iter()
                .find(|(o, a, _, _)| *o == os && *a == arch)
                .map(|p| p.2)
                .unwrap();
            let (stem, ext) = plain.split_once('.').unwrap();
            assert_eq!(asset, format!("{stem}-java.{ext}"));
        }
        assert_eq!(asset_for("macos", "aarch64"), None);
    }

    #[cfg(not(target_os = "macos"))]
    /// Replacing the build never leaves it half there: refused whole while the game holds it,
    /// moved back when the new one can't go in, and the old one gone once it did.
    #[test]
    fn a_build_is_replaced_whole_or_not_at_all() {
        let scratch = std::env::temp_dir().join(format!("hz-java-swap-{}", std::process::id()));
        let _ = fs::remove_dir_all(&scratch);
        let (new, to) = (scratch.join("new"), scratch.join(DIR));
        let reset = || {
            for (dir, file) in [(&new, "new.txt"), (&to, "old.txt")] {
                let _ = fs::remove_dir_all(dir);
                fs::create_dir_all(dir).unwrap();
                fs::write(dir.join(file), "").unwrap();
            }
        };
        let only = |dir: &Path, file: &str| {
            let names: Vec<_> = fs::read_dir(dir)
                .unwrap()
                .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .collect();
            assert_eq!(names, [file], "{}", dir.display());
        };
        let leftovers = || {
            let mut names: Vec<_> = fs::read_dir(&scratch)
                .unwrap()
                .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .collect();
            names.sort();
            names
        };

        reset();
        let held = |a: &Path, b: &Path| {
            if a.ends_with(DIR) {
                Err(std::io::Error::from(std::io::ErrorKind::PermissionDenied))
            } else {
                fs::rename(a, b)
            }
        };
        assert_eq!(swap(&new, &to, &held), Err(CLOSE_GAME_FIRST.to_string()));
        only(&to, "old.txt");
        only(&new, "new.txt");

        reset();
        let full = |a: &Path, b: &Path| {
            if a.ends_with("new") {
                Err(std::io::Error::other("disk full"))
            } else {
                fs::rename(a, b)
            }
        };
        assert!(swap(&new, &to, &full).unwrap_err().contains("disk full"));
        only(&to, "old.txt");
        assert_eq!(leftovers(), [DIR, "new"]);

        reset();
        swap(&new, &to, &|a, b| fs::rename(a, b)).unwrap();
        only(&to, "new.txt");
        assert_eq!(leftovers(), [DIR]);
        let _ = fs::remove_dir_all(&scratch);
    }

    /// Two downloads (the switch and Play at once) never run together: the second waits for
    /// the first, and a holder that panicked doesn't keep the lock shut.
    #[test]
    fn one_build_download_runs_at_a_time() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        static INSIDE: AtomicUsize = AtomicUsize::new(0);
        static MOST: AtomicUsize = AtomicUsize::new(0);
        let threads: Vec<_> = (0..4)
            .map(|_| {
                std::thread::spawn(|| {
                    let _one = one_at_a_time();
                    let now = INSIDE.fetch_add(1, Ordering::SeqCst) + 1;
                    MOST.fetch_max(now, Ordering::SeqCst);
                    std::thread::sleep(std::time::Duration::from_millis(20));
                    INSIDE.fetch_sub(1, Ordering::SeqCst);
                })
            })
            .collect();
        for t in threads {
            t.join().unwrap();
        }
        assert_eq!(MOST.load(Ordering::SeqCst), 1);
        let _ = std::thread::spawn(|| {
            let _one = one_at_a_time();
            panic!("a download failed badly");
        })
        .join();
        drop(one_at_a_time());
    }

    #[test]
    fn the_command_becomes_the_java_builds_with_its_arguments() {
        let scratch = std::env::temp_dir().join(format!("hz-java-variant-{}", std::process::id()));
        let version = scratch.join("v1.2.3");
        fs::create_dir_all(&version).unwrap();
        let mut cmd = Command::new(version.join(CLIENT));
        cmd.current_dir(&scratch)
            .arg("--resources")
            .arg("res")
            .env("HORIZON_HOME", &scratch)
            .env_remove("GONE");
        // No build yet: refused, the command left as it was.
        let err = switch_command(&mut cmd).unwrap_err();
        assert!(err.contains("v1.2.3"), "{err}");
        assert_eq!(PathBuf::from(cmd.get_program()), version.join(CLIENT));

        let staged = scratch.join("staged");
        fs::create_dir_all(&staged).unwrap();
        fs::write(staged.join(CLIENT), "").unwrap();
        place(&staged, &version.join(DIR), "").unwrap();
        switch_command(&mut cmd).unwrap();
        assert_eq!(
            PathBuf::from(cmd.get_program()),
            version.join(DIR).join(CLIENT)
        );
        let args: Vec<_> = cmd
            .get_args()
            .map(|a| a.to_string_lossy().into_owned())
            .collect();
        assert_eq!(args, ["--resources", "res", OPT_IN_ARG]);
        assert_eq!(cmd.get_current_dir(), Some(scratch.as_path()));
        let envs: Vec<_> = cmd.get_envs().collect();
        assert!(envs.contains(&("HORIZON_HOME".as_ref(), Some(scratch.as_os_str()))));
        assert!(envs.contains(&("GONE".as_ref(), None)));
        // The owner gate's word for the game, and never the developers' opt-in.
        assert!(envs.contains(&(OWNER_VAR.as_ref(), Some("1".as_ref()))));
        assert!(envs.contains(&(OPT_IN_VAR.as_ref(), None)));
        let _ = fs::remove_dir_all(&scratch);
    }
}
