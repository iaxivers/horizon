//! Fabric's client side for the jars in `HORIZON_TEST_MODS`, on this checkout's prepared 26.3:
//! the mods' client entrypoints run with Horizon's headless client (no window, no GPU), which
//! the mods' screens are drawn from (dev/horizonlab/platform/HorizonClient).
//!
//! ```sh
//! HORIZON_TEST_MODS=/path/to/mods cargo test -p horizon-jvm --test client_side -- --ignored --nocapture
//! ```

use std::path::{Path, PathBuf};

#[test]
#[ignore = "needs HORIZON_TEST_MODS, the prepared game (.local/minecraft) and Java 25"]
fn client_entrypoints_run_headless() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let home = horizon_jvm::find_java_home().expect("Java 25 (HORIZON_JAVA_HOME)");
    let work = root.join(".local/bridge");
    let source = PathBuf::from(std::env::var_os("HORIZON_TEST_MODS").expect("HORIZON_TEST_MODS"));
    let dir = std::env::temp_dir().join(format!("horizon-client-side-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let mods = dir.join("mods-java");
    std::fs::create_dir_all(&mods).unwrap();
    std::env::set_current_dir(&dir).unwrap();
    for jar in horizon_jvm::jars(&source) {
        std::fs::copy(&jar, mods.join(jar.file_name().unwrap())).unwrap();
    }
    horizon_jvm::set_log_sink(Some(|l: &str| println!("[java] {l}")));
    let config = horizon_jvm::Config {
        home,
        work: work.clone(),
        jars: horizon_jvm::jars(&mods),
        class_path: Vec::new(),
        fabric: Some(horizon_jvm::Fabric {
            game_jar: horizon_jvm::facade::game_jar(&root).unwrap(),
            libraries: horizon_jvm::facade::game_libraries(&root).unwrap(),
            add_mods: vec![horizon_jvm::libs::ensure_fabric_api(&work.join("fabric-api")).unwrap()],
            platform: true,
            mods: mods.clone(),
            game_dir: dir.clone(),
            version: "26.3".into(),
            side: horizon_jvm::Side::Client,
        }),
    };
    let bridge =
        horizon_jvm::start(&config).unwrap_or_else(|e| panic!("Fabric did not start: {e}"));
    println!("== started: {} mods", bridge.mods().len());
    std::fs::remove_dir_all(&dir).ok();
}
