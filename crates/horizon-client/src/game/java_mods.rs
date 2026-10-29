//! Java mods: the Java mod bridge (crate `horizon-jvm`), with the `java-mods` feature. When
//! `mods-java/` beside `options.txt` holds jars, the game starts the JVM and Fabric Loader on
//! a thread of their own, so the window never waits for them: Fabric loads the mods in
//! `mods-java/` and Fabric API on the player's own game jar, applies their Mixins,
//! bootstraps the game's registries and runs the mods' entrypoints.

use super::Game;
use super::execution_role::ExecutionRole;
use std::path::{Path, PathBuf};

/// Game and Fabric API classes loaded once through Fabric, to check the setup works.
const PROBE_CLASSES: &[&str] = &[
    "net/minecraft/world/level/block/Blocks",
    "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents",
];

impl Game<'_> {
    /// Starts the JVM and Fabric if `mods-java/` holds jars. No jars, no JVM: one folder read.
    pub(super) fn start_java_mods(&mut self) {
        let dir = Self::options_path().with_file_name(horizon_jvm::DIR);
        let jars = horizon_jvm::jars(&dir);
        if jars.is_empty() {
            return;
        }
        let root = checkout();
        let side = match self.options.execution_role {
            ExecutionRole::DedicatedServer => horizon_jvm::Side::Server,
            _ => horizon_jvm::Side::Client,
        };
        // The head of the players' hurtServer asks the Java mods.
        let _ = horizon_client::health::HURT_HOOK.set(super::server_mods::java_hurt);
        let _ = horizon_world::level::RANDOM_TICK_HOOK.set(super::server_mods::java_random_tick);
        let spawned = std::thread::Builder::new()
            .name("Java mods".into())
            .spawn(move || {
                start(&root, &dir, jars, side);
            });
        if let Err(e) = spawned {
            log::warn!("Java mods: no thread to start them on: {e}");
        }
    }
}

/// The checkout (or install) holding research-lock.json and the prepared jars.
fn checkout() -> PathBuf {
    std::env::var_os("HORIZON_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::current_dir().unwrap_or_default())
}

/// The content mods made from the Java mods in `mods-java/` (their blocks, items and assets,
/// horizon-jvm's `content`), for the game's content root over `base` (the prepared tables):
/// read back from the cache when the jars are the ones they were made from, else made now,
/// with Java and Fabric started for it (they stay up for the game).
pub(super) fn java_content(base: &Path) -> Vec<PathBuf> {
    let dir = Game::options_path().with_file_name(horizon_jvm::DIR);
    let jars = horizon_jvm::jars(&dir);
    if jars.is_empty() {
        return Vec::new();
    }
    let cache = std::env::current_dir()
        .unwrap_or_default()
        .join(".horizon-cache/java-content");
    if let Some(folders) = horizon_jvm::content::cached(&jars, &cache) {
        return folders;
    }
    let started = std::time::Instant::now();
    log::info!("Java mods: making their content (once for this set of mods)");
    let side = if std::env::args().any(|a| a == "--dedicated") {
        horizon_jvm::Side::Server
    } else {
        horizon_jvm::Side::Client
    };
    if !start(&checkout(), &dir, jars.clone(), side) {
        return Vec::new();
    }
    match horizon_jvm::content::build(&jars, base, &cache, &|l| log::info!("{l}")) {
        Ok(folders) => {
            log::info!(
                "Java mods: {} content mod(s) made in {} ms",
                folders.len(),
                started.elapsed().as_millis()
            );
            folders
        }
        Err(e) => {
            log::warn!("Java mods: their content was not made: {e}");
            Vec::new()
        }
    }
}

/// Starts the JVM and Fabric (once: again, the running one), then logs what loaded. Runs on
/// the "Java mods" thread, or before the resources load when the mods' content is made. Whether
/// they run.
fn start(root: &Path, dir: &Path, jars: Vec<PathBuf>, side: horizon_jvm::Side) -> bool {
    if horizon_jvm::get().is_some() {
        return true;
    }
    let started = std::time::Instant::now();
    let work = root.join(".local/bridge");
    let fabric = fabric(root, dir, &work, &jars, side);
    let fabric = match fabric {
        Ok(fabric) => Some(fabric),
        Err(e) => {
            log::warn!("Java mods: Fabric can't start: {e}");
            None
        }
    };
    let config = |home| horizon_jvm::Config {
        home,
        work: work.clone(),
        jars: jars.clone(),
        class_path: Vec::new(),
        fabric,
    };
    let bridge =
        match horizon_jvm::find_java_home().and_then(|home| horizon_jvm::start(&config(home))) {
            Ok(bridge) => bridge,
            Err(e) => {
                log::warn!("Java mods: not started: {e}");
                return false;
            }
        };
    log::info!(
        "Java mods: JVM {} from {} and {} started in {} ms, {} jar(s) in {}",
        bridge.java_version(),
        bridge.home().display(),
        if bridge.fabric() {
            "Fabric"
        } else {
            "no Fabric"
        },
        started.elapsed().as_millis(),
        jars.len(),
        dir.display()
    );
    if bridge.fabric() {
        let mods: Vec<&str> = bridge
            .mods()
            .iter()
            .map(String::as_str)
            .filter(|m| !m.starts_with("fabric-") || m.starts_with("fabric-api "))
            .collect();
        log::info!("Java mods: Fabric loaded {}", mods.join(", "));
        for class in PROBE_CLASSES {
            if let Err(e) = bridge.check_class(class) {
                log::warn!("Java mods: {class} failed to load: {e}");
            }
        }
    }
    true
}

/// Fabric's part of the config: the prepared game jar and libraries, and Fabric API unless
/// `mods-java/` holds its own.
fn fabric(
    root: &Path,
    dir: &Path,
    work: &Path,
    jars: &[PathBuf],
    side: horizon_jvm::Side,
) -> Result<horizon_jvm::Fabric, String> {
    let own_fabric_api = jars.iter().any(|j| {
        j.file_name().is_some_and(|n| {
            n.to_string_lossy()
                .to_ascii_lowercase()
                .starts_with("fabric-api")
        })
    });
    let add_mods = if own_fabric_api {
        Vec::new()
    } else {
        vec![horizon_jvm::libs::ensure_fabric_api(
            &work.join("fabric-api"),
        )?]
    };
    Ok(horizon_jvm::Fabric {
        game_jar: horizon_jvm::facade::game_jar(root)?,
        libraries: horizon_jvm::facade::game_libraries(root)?,
        add_mods,
        platform: true,
        mods: dir.to_path_buf(),
        game_dir: dir.parent().map(Path::to_path_buf).unwrap_or_default(),
        version: game_version(root)?,
        side,
    })
}

/// The game version research-lock.json pins.
fn game_version(root: &Path) -> Result<String, String> {
    let text = std::fs::read_to_string(root.join("research-lock.json"))
        .map_err(|e| format!("research-lock.json: {e}"))?;
    let json: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("research-lock.json: {e}"))?;
    json.pointer("/minecraft/version")
        .and_then(serde_json::Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| "research-lock.json has no minecraft version".into())
}
