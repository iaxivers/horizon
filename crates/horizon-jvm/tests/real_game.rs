//! Fabric on the real game: the prepared 26.3 client jar and libraries of this checkout
//! (`horizon-prepare --prepare`), Fabric API and the hello mod. Fabric must load them all,
//! bootstrap the game's registries and run the entrypoints.
//!
//! ```sh
//! HORIZON_JAVA_HOME=/usr/lib/jvm/java-25-openjdk cargo test -p horizon-jvm --test real_game -- --ignored --nocapture
//! ```

use std::path::{Path, PathBuf};
use std::sync::Mutex;

static LINES: Mutex<Vec<String>> = Mutex::new(Vec::new());

fn record(line: &str) {
    println!("[java] {line}");
    LINES.lock().unwrap().push(line.to_string());
}

#[test]
#[ignore = "needs the prepared game (.local/minecraft) and Java 25"]
fn fabric_api_and_a_mod_on_the_real_game() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let home = horizon_jvm::find_java_home().expect("Java 25 (HORIZON_JAVA_HOME)");
    let work = root.join(".local/bridge");
    let dir = std::env::temp_dir().join(format!("horizon-real-game-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let mods = dir.join("mods-java");
    std::fs::create_dir_all(&mods).unwrap();
    // The hello mod, as tests/fabric.rs's write_hello_mod makes it.
    let hello = std::env::var_os("HORIZON_HELLO_MOD").map(PathBuf::from);
    if let Some(hello) = &hello {
        std::fs::copy(hello, mods.join("horizon-hello.jar")).unwrap();
    }
    let fabric_api = horizon_jvm::libs::ensure_fabric_api(&work.join("fabric-api")).unwrap();
    horizon_jvm::set_log_sink(Some(record));
    let started = std::time::Instant::now();
    let config = horizon_jvm::Config {
        home,
        work: work.clone(),
        jars: horizon_jvm::jars(&mods),
        class_path: Vec::new(),
        fabric: Some(horizon_jvm::Fabric {
            game_jar: horizon_jvm::facade::game_jar(&root).unwrap(),
            libraries: horizon_jvm::facade::game_libraries(&root).unwrap(),
            add_mods: vec![fabric_api],
            mods: mods.clone(),
            game_dir: dir.clone(),
            version: "26.3".into(),
            side: horizon_jvm::Side::Client,
        }),
    };
    let bridge = match horizon_jvm::start(&config) {
        Ok(bridge) => bridge,
        Err(e) => panic!("Fabric did not start: {e}"),
    };
    println!("started in {} ms", started.elapsed().as_millis());
    let loaded = bridge.mods().to_vec();
    println!("{} mods: {}", loaded.len(), loaded.join(", "));
    assert!(
        loaded.iter().any(|m| m.starts_with("fabric-api ")),
        "{loaded:?}"
    );
    assert!(
        loaded
            .iter()
            .any(|m| m.starts_with("fabric-lifecycle-events-v1 "))
    );
    if hello.is_some() {
        assert!(loaded.contains(&"horizon_hello 1.0.0".to_string()));
    }
    let lines = LINES.lock().unwrap().clone();
    assert!(
        lines
            .iter()
            .any(|l| l.starts_with("Minecraft bootstrap done")),
        "{lines:?}"
    );
    for class in [
        "net/minecraft/world/level/block/Blocks",
        "net/minecraft/core/BlockPos",
        "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents",
    ] {
        bridge
            .check_class(class)
            .unwrap_or_else(|e| panic!("{class}: {e}"));
    }
    std::fs::remove_dir_all(&dir).ok();
}
