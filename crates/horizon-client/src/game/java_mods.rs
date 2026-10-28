//! Java mods: the embedded JVM of the Java mod bridge (crate `horizon-jvm`), with the
//! `java-mods` feature. Phase 1 starts the JVM when `mods-java/` beside `options.txt` holds
//! jars and logs what Java reports; loading the mods themselves comes with Fabric Loader.

use super::Game;

impl Game<'_> {
    /// Starts the JVM if `mods-java/` holds jars. No jars, no JVM: one folder read.
    pub(super) fn start_java_mods(&mut self) {
        let dir = Self::options_path().with_file_name(horizon_jvm::DIR);
        let jars = horizon_jvm::jars(&dir);
        if jars.is_empty() {
            return;
        }
        let started = std::time::Instant::now();
        match horizon_jvm::find_java_home().and_then(|home| horizon_jvm::start(&home, &jars)) {
            Ok(bridge) => log::info!(
                "Java mods: JVM {} from {} started in {} ms, {} jar(s) in {}",
                bridge.java_version(),
                bridge.home().display(),
                started.elapsed().as_millis(),
                jars.len(),
                dir.display()
            ),
            Err(e) => log::warn!("Java mods: not started: {e}"),
        }
    }
}
