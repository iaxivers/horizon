//! Java mods: the embedded JVM of the Java mod bridge (crate `horizon-jvm`), with the
//! `java-mods` feature. When `mods-java/` beside `options.txt` holds jars, the game builds
//! (once) the facade of the prepared 26.3 jars, starts the JVM with it on the class path and
//! checks a game class loads. Loading the mods themselves comes with Fabric Loader.

use super::Game;

/// A game class every facade holds, loaded once to check the facade works.
const PROBE_CLASS: &str = "net/minecraft/world/level/block/Block";

impl Game<'_> {
    /// Starts the JVM if `mods-java/` holds jars. No jars, no JVM: one folder read.
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
        let class_path = match horizon_jvm::facade::ensure(&root) {
            Ok(facade) => vec![facade],
            Err(e) => {
                log::warn!("Java mods: no facade, game classes unavailable: {e}");
                Vec::new()
            }
        };
        let bridge = match horizon_jvm::find_java_home()
            .and_then(|home| horizon_jvm::start(&home, &jars, &class_path))
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
        if !class_path.is_empty() {
            match bridge.check_class(PROBE_CLASS) {
                Ok(()) => log::info!("Java mods: facade OK, {PROBE_CLASS} loads"),
                Err(e) => log::warn!("Java mods: facade class {PROBE_CLASS} failed: {e}"),
            }
        }
    }
}
