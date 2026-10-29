//! Real mods from Modrinth on the real game: every jar in `HORIZON_TEST_MODS` (a folder),
//! with Fabric API and the platform mod, on this checkout's prepared 26.3. A player (Steve,
//! an operator) joins, then the script `HORIZON_TEST_SCRIPT` runs, one step per line:
//!
//! - `/command args`: Steve types it;
//! - `ticks N`: N server ticks pass;
//! - `move x y z`: Steve is now there (Horizon moved him);
//! - `mode N`: Steve's game mode is now N (0 survival, 1 creative, 2 adventure, 3 spectator);
//! - `say text`: Steve chats.
//!
//! Every answer, teleport and failure is printed: this is how a mod's needs are found.
//!
//! ```sh
//! HORIZON_TEST_MODS=/path/to/mods HORIZON_TEST_SCRIPT='/sethome base
//! move 100 70 100
//! /home base' cargo test -p horizon-jvm --test modrinth -- --ignored --nocapture
//! ```

use horizon_jvm::events::{Action, Event, Player};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

static LINES: Mutex<Vec<String>> = Mutex::new(Vec::new());

fn record(line: &str) {
    println!("[java] {line}");
    LINES.lock().unwrap().push(line.to_string());
}

#[test]
#[ignore = "needs HORIZON_TEST_MODS, the prepared game (.local/minecraft) and Java 25"]
fn real_mods_in_a_scripted_session() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let home = horizon_jvm::find_java_home().expect("Java 25 (HORIZON_JAVA_HOME)");
    let work = root.join(".local/bridge");
    let source = PathBuf::from(std::env::var_os("HORIZON_TEST_MODS").expect("HORIZON_TEST_MODS"));
    let dir = std::env::temp_dir().join(format!("horizon-modrinth-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let mods = dir.join("mods-java");
    std::fs::create_dir_all(&mods).unwrap();
    // Mods write relative paths (./config/...) where the process runs: keep them here.
    std::env::set_current_dir(&dir).unwrap();
    for jar in horizon_jvm::jars(&source) {
        std::fs::copy(&jar, mods.join(jar.file_name().unwrap())).unwrap();
    }
    let fabric_api = horizon_jvm::libs::ensure_fabric_api(&work.join("fabric-api")).unwrap();
    horizon_jvm::set_log_sink(Some(record));
    let started = std::time::Instant::now();
    let config = horizon_jvm::Config {
        home,
        work,
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
            side: horizon_jvm::Side::Server,
        }),
    };
    let bridge = match horizon_jvm::start(&config) {
        Ok(bridge) => bridge,
        Err(e) => panic!("Fabric did not start: {e}"),
    };
    println!("== started in {} ms", started.elapsed().as_millis());
    let loaded: Vec<&String> = bridge
        .mods()
        .iter()
        .filter(|m| !m.starts_with("fabric-") || m.starts_with("fabric-api "))
        .collect();
    println!("== mods: {loaded:?}");
    let names = horizon_jvm::events::command_names().unwrap();
    println!("== commands: {}", names.join(" "));

    let mut steve = Player {
        uuid: "00000000-0000-4000-8000-000000000001".into(),
        name: "Steve".into(),
        position: [0.5, 64.0, 0.5],
        yaw: 0.0,
        pitch: 0.0,
        dimension: "minecraft:overworld".into(),
        game_mode: 0,
        health: 20.0,
        permission: 4,
    };
    let uuid = steve.uuid.clone();
    let world = dir.join("world");
    let mut tick = 0i64;
    let step = |steve: &Player, events: &[Event], tick: &mut i64| {
        *tick += 1;
        horizon_jvm::events::server_tick(*tick, true, &world, std::slice::from_ref(steve), events)
            .unwrap();
        horizon_jvm::events::take_actions()
    };
    let show = |what: &str, actions: Vec<Action>| {
        println!("== {what}");
        for action in actions {
            println!("   {action:?}");
        }
    };
    show(
        "join",
        step(&steve, &[Event::Joined { uuid: uuid.clone() }], &mut tick),
    );
    let script = std::env::var("HORIZON_TEST_SCRIPT").unwrap_or_default();
    for line in script.lines().map(str::trim).filter(|l| !l.is_empty()) {
        if let Some(command) = line.strip_prefix('/') {
            let ran = horizon_jvm::events::run_command(&uuid, command).unwrap();
            let mut actions = horizon_jvm::events::take_actions();
            // What a command schedules happens on the next tick.
            actions.extend(step(&steve, &[], &mut tick));
            show(&format!("/{command} (ran: {ran})"), actions);
        } else if let Some(n) = line.strip_prefix("ticks ") {
            let mut actions = Vec::new();
            for _ in 0..n.parse::<u32>().unwrap() {
                actions.extend(step(&steve, &[], &mut tick));
            }
            show(line, actions);
        } else if let Some(xyz) = line.strip_prefix("move ") {
            let v: Vec<f64> = xyz.split_whitespace().map(|v| v.parse().unwrap()).collect();
            steve.position = [v[0], v[1], v[2]];
            show(line, step(&steve, &[], &mut tick));
        } else if let Some(mode) = line.strip_prefix("mode ") {
            steve.game_mode = mode.parse().unwrap();
            show(line, step(&steve, &[], &mut tick));
        } else if let Some(text) = line.strip_prefix("say ") {
            let chat = Event::Chat {
                uuid: uuid.clone(),
                text: text.into(),
            };
            show(line, step(&steve, &[chat], &mut tick));
        } else {
            panic!("unknown script line: {line}");
        }
    }
    show(
        "leave",
        step(&steve, &[Event::Left { uuid: uuid.clone() }], &mut tick),
    );
    let failures: Vec<String> = LINES
        .lock()
        .unwrap()
        .iter()
        .filter(|l| l.starts_with("A mod failed") || l.starts_with("An error occurred"))
        .cloned()
        .collect();
    println!("== {} failure(s)", failures.len());
    std::fs::remove_dir_all(&dir).ok();
}
