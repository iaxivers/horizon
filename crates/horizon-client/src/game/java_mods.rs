//! Java mods: the Java mod bridge (crate `horizon-jvm`), with the `java-mods` feature. When
//! `mods-java/` beside `options.txt` holds jars, the game builds (once) the facade of the
//! prepared game jars, starts the JVM and Fabric Loader on it: Fabric finds the mods in
//! `mods-java/`, applies their Mixins to the facade and runs their entrypoints.

use super::Game;
use super::execution_role::ExecutionRole;

/// A game class every facade holds, loaded once through Fabric to check the facade works.
const PROBE_CLASS: &str = "net/minecraft/world/level/block/Block";

impl Game<'_> {
    /// Starts the JVM and Fabric if `mods-java/` holds jars. No jars, no JVM: one folder read.
    pub(super) fn start_java_mods(&mut self) {
        let dir = Self::options_path().with_file_name(horizon_jvm::DIR);
        let jars = horizon_jvm::jars(&dir);
        if jars.is_empty() {
            return;
        }
        let started = std::time::Instant::now();
        // The checkout (or install) holding research-lock.json and the prepared jars.
        let root = std::env::var_os("HORIZON_ROOT")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|| std::env::current_dir().unwrap_or_default());
        let game_dir = dir
            .parent()
            .map(std::path::Path::to_path_buf)
            .unwrap_or_default();
        let side = match self.options.execution_role {
            ExecutionRole::DedicatedServer => horizon_jvm::Side::Server,
            _ => horizon_jvm::Side::Client,
        };
        let fabric = horizon_jvm::facade::ensure(&root).and_then(|facade| {
            let lock = horizon_prepare_lock(&root)?;
            let libraries = horizon_jvm::facade::game_libraries(&root)?;
            Ok(horizon_jvm::Fabric {
                facade,
                libraries,
                mods: dir.clone(),
                game_dir,
                version: lock,
                side,
            })
        });
        let fabric = match fabric {
            Ok(fabric) => Some(fabric),
            Err(e) => {
                log::warn!("Java mods: no facade, so no Fabric: {e}");
                None
            }
        };
        let config = |home| horizon_jvm::Config {
            home,
            work: root.join(".local/bridge"),
            jars: jars.clone(),
            class_path: Vec::new(),
            fabric,
        };
        let bridge = match horizon_jvm::find_java_home()
            .and_then(|home| horizon_jvm::start(&config(home)))
        {
            Ok(bridge) => bridge,
            Err(e) => {
                log::warn!("Java mods: not started: {e}");
                return;
            }
        };
        log::info!(
            "Java mods: JVM {} from {} started in {} ms, {} jar(s) in {}",
            bridge.java_version(),
            bridge.home().display(),
            started.elapsed().as_millis(),
            jars.len(),
            dir.display()
        );
        if bridge.fabric() {
            log::info!("Java mods: Fabric loaded {}", bridge.mods().join(", "));
            match bridge.check_class(PROBE_CLASS) {
                Ok(()) => log::info!("Java mods: facade OK, {PROBE_CLASS} loads through Fabric"),
                Err(e) => log::warn!("Java mods: facade class {PROBE_CLASS} failed: {e}"),
            }
        }
    }
}

/// The game version research-lock.json pins.
fn horizon_prepare_lock(root: &std::path::Path) -> Result<String, String> {
    let text = std::fs::read_to_string(root.join("research-lock.json"))
        .map_err(|e| format!("research-lock.json: {e}"))?;
    let json: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("research-lock.json: {e}"))?;
    json.pointer("/minecraft/version")
        .and_then(serde_json::Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| "research-lock.json has no minecraft version".into())
}
