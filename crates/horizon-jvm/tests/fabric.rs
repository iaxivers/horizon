//! Fabric Loader inside Horizon's JVM, end to end: a stand-in game's facade, a Fabric test mod
//! (`java/fixtures/testmod/`: two entrypoints and a Mixin on a game class) in a mods folder,
//! then `start` with Fabric. The mod must be found, its entrypoints run, and its Mixin must
//! change what the facade's stub returns.
//!
//! Needs a JDK (`HORIZON_JAVA_HOME`, `JAVA_HOME` or `javac` on the PATH) and Fabric Loader's
//! jars (downloaded into `.local/bridge/lib/` like build.rs does); without a JDK it passes.

use jni::objects::{JObject, JValue};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

static LINES: Mutex<Vec<String>> = Mutex::new(Vec::new());

fn record(line: &str) {
    LINES.lock().unwrap().push(line.to_string());
}

fn java_home() -> Option<PathBuf> {
    for var in [horizon_jvm::JAVA_HOME_VAR, "JAVA_HOME"] {
        if let Some(home) = std::env::var_os(var).filter(|h| !h.is_empty()) {
            return Some(PathBuf::from(home));
        }
    }
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|dir| dir.join(if cfg!(windows) { "javac.exe" } else { "javac" }))
        .find(|javac| javac.is_file())
        .and_then(|javac| std::fs::canonicalize(javac).ok())
        .and_then(|javac| javac.parent()?.parent().map(Path::to_path_buf))
}

fn javac(home: &Path, class_path: &[PathBuf], extra: &[&str], out: &Path, sources: &Path) {
    let mut files = Vec::new();
    collect(sources, "java", &mut files);
    files.sort();
    let mut command = std::process::Command::new(home.join("bin").join(if cfg!(windows) {
        "javac.exe"
    } else {
        "javac"
    }));
    command.args(["--release", "21", "-nowarn", "-d"]).arg(out);
    if !class_path.is_empty() {
        command
            .arg("-cp")
            .arg(std::env::join_paths(class_path).unwrap());
    }
    let status = command.args(extra).args(&files).status().unwrap();
    assert!(status.success(), "javac {}", sources.display());
}

fn collect(dir: &Path, ext: &str, out: &mut Vec<PathBuf>) {
    for entry in std::fs::read_dir(dir).unwrap().flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect(&path, ext, out);
        } else if path.extension().is_some_and(|e| e == ext) {
            out.push(path);
        }
    }
}

/// Packs every file under `root` (and `extra` files at the top) into `jar`.
fn pack(root: &Path, extra: &[&Path], jar: &Path) {
    let mut zip = zip::ZipWriter::new(std::fs::File::create(jar).unwrap());
    let options = zip::write::SimpleFileOptions::default();
    let mut files = Vec::new();
    collect(root, "class", &mut files);
    files.sort();
    for file in files {
        let name = file
            .strip_prefix(root)
            .unwrap()
            .to_string_lossy()
            .replace('\\', "/");
        zip.start_file(name, options).unwrap();
        zip.write_all(&std::fs::read(&file).unwrap()).unwrap();
    }
    for file in extra {
        zip.start_file(file.file_name().unwrap().to_string_lossy(), options)
            .unwrap();
        zip.write_all(&std::fs::read(file).unwrap()).unwrap();
    }
    zip.finish().unwrap();
}

#[test]
fn fabric_loads_a_mod_runs_its_entrypoints_and_applies_its_mixin() {
    let Some(home) = java_home() else {
        eprintln!("no JDK found: set HORIZON_JAVA_HOME to run this test");
        return;
    };
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    let dir = std::env::temp_dir().join(format!("horizon-fabric-test-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    // The checkout's own bridge folder: the jars build.rs downloaded are there already.
    let work = manifest.join("../../.local/bridge");
    let libs = horizon_jvm::libs::ensure(&work.join("lib")).expect("Fabric Loader's jars");

    // The stand-in game and its facade.
    let game_classes = dir.join("game");
    javac(
        &home,
        &[],
        &[],
        &game_classes,
        &manifest.join("java/fixtures/dev"),
    );
    let game = dir.join("game.jar");
    pack(&game_classes, &[], &game);
    let facade = dir.join("facade.jar");
    horizon_jvm::facade::build(&[game], &facade).unwrap();

    // The test mod, compiled against the real game classes, Fabric and the bridge's sources
    // (-implicit:none: the bridge's classes are referenced, not packed into the mod).
    let mod_classes = dir.join("mod");
    let mut class_path = libs.clone();
    class_path.push(game_classes.clone());
    let bridge_sources = manifest.join("java");
    javac(
        &home,
        &class_path,
        &[
            "-implicit:none",
            "-proc:none",
            "-sourcepath",
            bridge_sources.to_str().unwrap(),
        ],
        &mod_classes,
        &manifest.join("java/fixtures/testmod"),
    );
    let mods = dir.join("mods");
    std::fs::create_dir_all(&mods).unwrap();
    let testmod = manifest.join("java/fixtures/testmod");
    pack(
        &mod_classes,
        &[
            &testmod.join("fabric.mod.json"),
            &testmod.join("horizon_test.mixins.json"),
        ],
        &mods.join("horizon_test.jar"),
    );

    horizon_jvm::set_log_sink(Some(record));
    let config = horizon_jvm::Config {
        home: home.clone(),
        work,
        jars: horizon_jvm::jars(&mods),
        class_path: Vec::new(),
        fabric: Some(horizon_jvm::Fabric {
            game_jar: facade,
            libraries: Vec::new(),
            add_mods: Vec::new(),
            platform: false,
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
    assert!(bridge.fabric());
    let mods_loaded = bridge.mods().to_vec();
    assert!(
        mods_loaded.contains(&"horizon_test 1.0.0".to_string()),
        "{mods_loaded:?}"
    );
    assert!(
        mods_loaded.contains(&"minecraft 26.3".to_string()),
        "{mods_loaded:?}"
    );
    let lines = LINES.lock().unwrap().clone();
    assert!(
        lines.iter().any(|l| l == "horizon_test: onInitialize ran"),
        "{lines:?}"
    );
    assert!(
        lines
            .iter()
            .any(|l| l == "horizon_test: onInitializeClient ran"),
        "{lines:?}"
    );

    // The Mixin: Base.answer() is a stub returning 0 in the facade; the mod makes it 99.
    let mut env = bridge.vm().attach_current_thread().unwrap();
    let mut run = || -> jni::errors::Result<i32> {
        let base = bridge.find_class(&mut env, "dev/horizonlab/fixture/Base")?;
        let object = env.new_object(
            &base,
            "(Ljava/lang/String;J)V",
            &[JValue::Object(&JObject::null()), JValue::Long(0)],
        )?;
        env.call_method(&object, "answer", "()I", &[])?.i()
    };
    let answer = match run() {
        Ok(answer) => answer,
        Err(e) => {
            env.exception_describe().ok();
            panic!("{e}");
        }
    };
    assert_eq!(answer, 99, "the mod's Mixin applies to the facade's class");
    assert!(bridge.check_class("dev/horizonlab/fixture/Sub").is_ok());
    std::fs::remove_dir_all(&dir).ok();
}

/// Writes the hello mod's jar (`java/fixtures/hellomod/`) to `HORIZON_HELLO_MOD`, for a real
/// game's `mods-java/`:
///
/// ```sh
/// HORIZON_HELLO_MOD=$PWD/mods-java/horizon-hello.jar cargo test -p horizon-jvm --test fabric -- --ignored write_hello_mod
/// ```
#[test]
#[ignore = "writes a jar to HORIZON_HELLO_MOD"]
fn write_hello_mod() {
    let out = PathBuf::from(std::env::var_os("HORIZON_HELLO_MOD").expect("HORIZON_HELLO_MOD"));
    let home = java_home().expect("a JDK (HORIZON_JAVA_HOME)");
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    let libs = horizon_jvm::libs::ensure(&manifest.join("../../.local/bridge/lib")).unwrap();
    let dir = std::env::temp_dir().join(format!("horizon-hello-mod-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let source = manifest.join("java/fixtures/hellomod");
    let bridge_sources = manifest.join("java");
    javac(
        &home,
        &libs,
        &[
            "-implicit:none",
            "-proc:none",
            "-sourcepath",
            bridge_sources.to_str().unwrap(),
        ],
        &dir,
        &source,
    );
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent).unwrap();
    }
    pack(&dir, &[&source.join("fabric.mod.json")], &out);
    std::fs::remove_dir_all(&dir).ok();
    println!("wrote {}", out.display());
}
