//! Fabric on the real game: the prepared 26.3 client jar and libraries of this checkout
//! (`horizon-prepare --prepare`), Fabric API, the platform mod and two test mods: the hello
//! mod and the events mod (`java/fixtures/eventsmod/`, a plain server-side Fabric mod). Fabric
//! must load them all, bootstrap the game's registries and run the entrypoints; then a player
//! joins, chats, breaks a block and leaves through `events::server_tick`, and the events mod's
//! answers must come back as actions.
//!
//! ```sh
//! HORIZON_JAVA_HOME=/usr/lib/jvm/java-25-openjdk cargo test -p horizon-jvm --test real_game -- --ignored --nocapture
//! ```

use horizon_jvm::events::{Action, Event, Player};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

static LINES: Mutex<Vec<String>> = Mutex::new(Vec::new());

fn record(line: &str) {
    println!("[java] {line}");
    LINES.lock().unwrap().push(line.to_string());
}

fn collect(dir: &Path, ext: &str, out: &mut Vec<PathBuf>) {
    for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect(&path, ext, out);
        } else if path.extension().is_some_and(|e| e == ext) {
            out.push(path);
        }
    }
}

/// Compiles the events mod against the game, its libraries, Fabric Loader and Fabric API's
/// modules, and packs it into `mods`.
fn events_mod(root: &Path, home: &Path, fabric_api: &Path, dir: &Path, mods: &Path) {
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    let modules = dir.join("fabric-api-modules");
    std::fs::create_dir_all(&modules).unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(fabric_api).unwrap()).unwrap();
    let mut class_path = Vec::new();
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).unwrap();
        if let Some(file) = entry.name().strip_prefix("META-INF/jars/") {
            let path = modules.join(file);
            let mut bytes = Vec::new();
            std::io::Read::read_to_end(&mut entry, &mut bytes).unwrap();
            std::fs::write(&path, bytes).unwrap();
            class_path.push(path);
        }
    }
    class_path.push(horizon_jvm::facade::game_jar(root).unwrap());
    class_path.extend(horizon_jvm::facade::game_libraries(root).unwrap());
    class_path.extend(horizon_jvm::libs::ensure(&root.join(".local/bridge/lib")).unwrap());
    let source = manifest.join("java/fixtures/eventsmod");
    let mut files = Vec::new();
    collect(&source, "java", &mut files);
    let classes = dir.join("events-classes");
    let status = std::process::Command::new(home.join("bin/javac"))
        .args(["--release", "21", "-nowarn", "-proc:none", "-cp"])
        .arg(std::env::join_paths(&class_path).unwrap())
        .arg("-d")
        .arg(&classes)
        .args(&files)
        .status()
        .unwrap();
    assert!(status.success(), "javac on the events mod");
    let mut zip =
        zip::ZipWriter::new(std::fs::File::create(mods.join("horizon-events-test.jar")).unwrap());
    let options = zip::write::SimpleFileOptions::default();
    let mut class_files = Vec::new();
    collect(&classes, "class", &mut class_files);
    for file in class_files {
        let name = file
            .strip_prefix(&classes)
            .unwrap()
            .to_string_lossy()
            .replace('\\', "/");
        zip.start_file(name, options).unwrap();
        zip.write_all(&std::fs::read(&file).unwrap()).unwrap();
    }
    zip.start_file("fabric.mod.json", options).unwrap();
    zip.write_all(&std::fs::read(source.join("fabric.mod.json")).unwrap())
        .unwrap();
    zip.finish().unwrap();
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
    events_mod(&root, &home, &fabric_api, &dir, &mods);
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
            platform: true,
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
    assert!(
        loaded.contains(&"horizon 0.1.0".to_string()),
        "the platform mod: {loaded:?}"
    );
    assert!(
        loaded.contains(&"horizon_events_test 1.0.0".to_string()),
        "{loaded:?}"
    );
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

    // Horizon's side: a player joins, chats, breaks a block, ticks pass, the player leaves.
    assert!(horizon_jvm::events::enabled(), "the platform mod runs");
    let steve = Player {
        uuid: "00000000-0000-4000-8000-000000000001".into(),
        name: "Steve".into(),
        position: [10.5, 64.0, -3.5],
        yaw: 90.0,
        pitch: 0.0,
        dimension: "minecraft:overworld".into(),
        game_mode: 0,
        health: 17.0,
    };
    let uuid = steve.uuid.clone();
    let tick = |n: i64, players: &[Player], events: &[Event]| {
        horizon_jvm::events::server_tick(n, false, players, events).unwrap();
        horizon_jvm::events::take_actions()
    };
    let actions = tick(
        1,
        std::slice::from_ref(&steve),
        &[Event::Joined { uuid: uuid.clone() }],
    );
    println!("tick 1: {actions:?}");
    assert!(
        actions.contains(&Action::Broadcast {
            text: "[events] server started".into()
        }),
        "{actions:?}"
    );
    assert!(
        actions.contains(&Action::Tell {
            uuid: uuid.clone(),
            text: "[events] Welcome Steve at 10, 64, -4 in minecraft:overworld, health 17.0".into(),
        }),
        "{actions:?}"
    );
    let actions = tick(
        2,
        std::slice::from_ref(&steve),
        &[
            Event::Chat {
                uuid: uuid.clone(),
                text: "hello".into(),
            },
            Event::Block {
                uuid: uuid.clone(),
                position: [10, 63, -4],
                state: "minecraft:oak_log[axis=y]".into(),
                placed: false,
            },
        ],
    );
    println!("tick 2: {actions:?}");
    assert!(
        actions.contains(&Action::Tell {
            uuid: uuid.clone(),
            text: "[events] you said: hello".into()
        }),
        "{actions:?}"
    );
    assert!(
        actions.contains(&Action::Tell {
            uuid: uuid.clone(),
            text: "[events] you broke minecraft:oak_log at 10, 63, -4".into(),
        }),
        "{actions:?}"
    );
    let started = std::time::Instant::now();
    let mut counted = Vec::new();
    for n in 3..=100 {
        counted.extend(tick(n, std::slice::from_ref(&steve), &[]));
    }
    println!("98 ticks in {} ms", started.elapsed().as_millis());
    assert_eq!(
        counted,
        vec![Action::Broadcast {
            text: "[events] 100 ticks, 1 players on".into()
        }]
    );
    let actions = tick(101, &[], &[Event::Left { uuid: uuid.clone() }]);
    println!("tick 101: {actions:?}");
    assert!(
        actions.contains(&Action::Broadcast {
            text: "[events] Steve left".into()
        }),
        "{actions:?}"
    );
    let lines = LINES.lock().unwrap().clone();
    assert!(
        !lines.iter().any(|l| l.starts_with("A mod failed")),
        "{lines:?}"
    );
    std::fs::remove_dir_all(&dir).ok();
}

/// Writes the events mod's jar into the folder `HORIZON_EVENTS_MOD_DIR` (a real game's
/// `mods-java/`), compiled against this checkout's prepared game and Fabric API:
///
/// ```sh
/// HORIZON_EVENTS_MOD_DIR=$PWD/mods-java cargo test -p horizon-jvm --test real_game -- --ignored write_events_mod
/// ```
#[test]
#[ignore = "writes a jar into HORIZON_EVENTS_MOD_DIR"]
fn write_events_mod() {
    let out =
        PathBuf::from(std::env::var_os("HORIZON_EVENTS_MOD_DIR").expect("HORIZON_EVENTS_MOD_DIR"));
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let home = horizon_jvm::find_java_home().expect("Java 25 (HORIZON_JAVA_HOME)");
    let fabric_api =
        horizon_jvm::libs::ensure_fabric_api(&root.join(".local/bridge/fabric-api")).unwrap();
    let dir = std::env::temp_dir().join(format!("horizon-events-mod-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::create_dir_all(&out).unwrap();
    events_mod(&root, &home, &fabric_api, &dir, &out);
    std::fs::remove_dir_all(&dir).ok();
    println!("wrote {}", out.join("horizon-events-test.jar").display());
}
