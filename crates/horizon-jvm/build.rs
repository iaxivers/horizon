//! Builds the bridge's two jars, which `lib.rs` embeds:
//!
//! - `horizon-bridge.jar` (`java/dev/horizonlab/bridge/`): compiled by `javac` against Fabric
//!   Loader and its libraries (`fabric-libs.tsv`, downloaded once into `.local/bridge/lib/` of
//!   the checkout, or `HORIZON_BRIDGE_LIBS`), plus the service file that lets Fabric Loader find
//!   `HorizonGameProvider`.
//! - `horizon-platform.jar` (`java/platform/`, the Fabric mod `horizon`): compiled against the
//!   game itself, as `horizon-prepare --prepare` left it in `.local/minecraft/<version>/`, and
//!   Fabric API (`fabric-api.tsv`). Without a prepared game it is left empty, and the game runs
//!   Fabric with no game events for mods.
//!
//! Needs a JDK at build time (JDK 25 for Horizon; any JDK 21 or newer compiles these):
//! `JAVA_HOME`, else `javac` on the PATH.

#[allow(dead_code)]
#[path = "src/libs.rs"]
mod libs;

use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;

fn javac() -> PathBuf {
    let name = if cfg!(windows) { "javac.exe" } else { "javac" };
    if let Some(home) = std::env::var_os("JAVA_HOME").filter(|h| !h.is_empty()) {
        let javac = Path::new(&home).join("bin").join(name);
        if javac.is_file() {
            return javac;
        }
    }
    PathBuf::from(name)
}

fn sources(dir: &Path, out: &mut Vec<PathBuf>) {
    for entry in std::fs::read_dir(dir).expect("read java/").flatten() {
        let path = entry.path();
        if path.is_dir() {
            sources(&path, out);
        } else if path.extension().is_some_and(|e| e == "java") {
            out.push(path);
        }
    }
}

fn main() {
    println!("cargo:rerun-if-changed=java/dev");
    println!("cargo:rerun-if-changed=fabric-libs.tsv");
    println!("cargo:rerun-if-changed=fabric-api.tsv");
    println!("cargo:rerun-if-changed=src/libs.rs");
    println!("cargo:rerun-if-env-changed=JAVA_HOME");
    println!("cargo:rerun-if-env-changed=HORIZON_BRIDGE_LIBS");
    let manifest =
        PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let libs_dir = std::env::var_os("HORIZON_BRIDGE_LIBS")
        .map(PathBuf::from)
        .unwrap_or_else(|| manifest.join("../../.local/bridge/lib"));
    let libs = libs::ensure(&libs_dir).unwrap_or_else(|e| {
        panic!(
            "horizon-jvm needs Fabric Loader's jars to build (feature java-mods): {e}. \
             With no network, put the files of fabric-libs.tsv in {} or set HORIZON_BRIDGE_LIBS.",
            libs_dir.display()
        )
    });

    let out = PathBuf::from(std::env::var_os("OUT_DIR").expect("OUT_DIR"));
    let classes = out.join("classes");
    let _ = std::fs::remove_dir_all(&classes);
    std::fs::create_dir_all(&classes).expect("create OUT_DIR/classes");
    let mut files = Vec::new();
    sources(&manifest.join("java/dev"), &mut files);
    files.sort();
    let class_path = std::env::join_paths(&libs).expect("class path");
    let javac = javac();
    let status = Command::new(&javac)
        // Class files Java 21 and newer load; Horizon's players run Java 25.
        .args([
            "--release",
            "21",
            "-encoding",
            "UTF-8",
            "-nowarn",
            "-proc:none",
            "-cp",
        ])
        .arg(&class_path)
        .arg("-d")
        .arg(&classes)
        .args(&files)
        .status()
        .unwrap_or_else(|e| {
            panic!(
                "horizon-jvm needs a JDK to build (feature java-mods): could not run {}: {e}. \
                 Install JDK 25 or set JAVA_HOME.",
                javac.display()
            )
        });
    assert!(
        status.success(),
        "javac failed on horizon-jvm's Java sources"
    );

    let jar = out.join("horizon-bridge.jar");
    let mut zip = zip::ZipWriter::new(std::fs::File::create(&jar).expect("create bridge jar"));
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .last_modified_time(zip::DateTime::default());
    let mut class_files = Vec::new();
    collect(&classes, &mut class_files);
    class_files.sort();
    for file in class_files {
        let name = file
            .strip_prefix(&classes)
            .unwrap()
            .to_string_lossy()
            .replace('\\', "/");
        zip.start_file(name, options).unwrap();
        zip.write_all(&std::fs::read(&file).unwrap()).unwrap();
    }
    zip.start_file(
        "META-INF/services/net.fabricmc.loader.impl.game.GameProvider",
        options,
    )
    .unwrap();
    zip.write_all(b"dev.horizonlab.bridge.fabric.HorizonGameProvider\n")
        .unwrap();
    zip.finish().unwrap();

    platform(&manifest, &out, &libs, &classes, &javac);
}

/// The prepared game of this checkout: its client jar and libraries, if prepared.
fn prepared_game(root: &Path) -> Option<(PathBuf, Vec<PathBuf>)> {
    let lock = std::fs::read_to_string(root.join("research-lock.json")).ok()?;
    // "minecraft": { "version": "26.3", ... }
    let after = &lock[lock.find("\"minecraft\"")?..];
    let after = &after[after.find("\"version\"")? + "\"version\"".len()..];
    let start = after.find('"')? + 1;
    let version = &after[start..start + after[start..].find('"')?];
    let dir = root.join(".local/minecraft").join(version);
    let jar = dir.join("jars/client.jar");
    if !jar.is_file() {
        return None;
    }
    let mut libraries = Vec::new();
    collect_ext(&dir.join("libraries/client"), "jar", &mut libraries);
    libraries.sort();
    Some((jar, libraries))
}

/// Fabric API's module jars, unpacked from its jar-in-jar into `dir` (javac can't read jars
/// inside jars).
fn fabric_api_modules(jar: &Path, dir: &Path) -> Vec<PathBuf> {
    let _ = std::fs::remove_dir_all(dir);
    std::fs::create_dir_all(dir).unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(jar).unwrap()).unwrap();
    let mut modules = Vec::new();
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).unwrap();
        let name = entry.name().to_string();
        if let Some(file) = name.strip_prefix("META-INF/jars/") {
            let path = dir.join(file);
            let mut bytes = Vec::new();
            std::io::Read::read_to_end(&mut entry, &mut bytes).unwrap();
            std::fs::write(&path, bytes).unwrap();
            modules.push(path);
        }
    }
    modules.sort();
    modules
}

/// Builds `horizon-platform.jar`, or an empty file when there is no prepared game.
fn platform(manifest: &Path, out: &Path, libs: &[PathBuf], bridge_classes: &Path, javac: &Path) {
    println!("cargo:rerun-if-changed=java/platform");
    let jar_path = out.join("horizon-platform.jar");
    let root = manifest.join("../..");
    let Some((game, game_libraries)) = prepared_game(&root) else {
        println!(
            "cargo:warning=horizon-jvm: no prepared game in .local/minecraft, so no platform mod: \
             Java mods load but get no game events. Run horizon-prepare --prepare, then rebuild."
        );
        std::fs::write(&jar_path, b"").unwrap();
        return;
    };
    println!("cargo:rerun-if-changed={}", game.display());
    let fabric_api = libs::ensure_fabric_api(&root.join(".local/bridge/fabric-api"))
        .unwrap_or_else(|e| panic!("horizon-jvm's platform mod needs Fabric API to build: {e}"));
    let modules = fabric_api_modules(&fabric_api, &out.join("fabric-api-modules"));

    let sources_dir = manifest.join("java/platform");
    let mut files = Vec::new();
    collect_ext(&sources_dir, "java", &mut files);
    files.sort();
    let classes = out.join("platform-classes");
    let _ = std::fs::remove_dir_all(&classes);
    std::fs::create_dir_all(&classes).unwrap();
    let mut class_path: Vec<PathBuf> = libs.to_vec();
    class_path.push(game);
    class_path.extend(game_libraries);
    class_path.extend(modules);
    class_path.push(bridge_classes.to_path_buf());
    let status = Command::new(javac)
        .args([
            "--release",
            "21",
            "-encoding",
            "UTF-8",
            "-nowarn",
            "-proc:none",
            "-cp",
        ])
        .arg(std::env::join_paths(&class_path).unwrap())
        .arg("-d")
        .arg(&classes)
        .args(&files)
        .status()
        .expect("javac");
    assert!(
        status.success(),
        "javac failed on horizon-jvm's platform mod (java/platform/)"
    );

    let mut zip = zip::ZipWriter::new(std::fs::File::create(&jar_path).unwrap());
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .last_modified_time(zip::DateTime::default());
    let mut class_files = Vec::new();
    collect(&classes, &mut class_files);
    class_files.sort();
    for file in class_files {
        let name = file
            .strip_prefix(&classes)
            .unwrap()
            .to_string_lossy()
            .replace('\\', "/");
        zip.start_file(name, options).unwrap();
        zip.write_all(&std::fs::read(&file).unwrap()).unwrap();
    }
    for resource in ["fabric.mod.json", "horizon-platform.mixins.json"] {
        zip.start_file(resource, options).unwrap();
        zip.write_all(&std::fs::read(sources_dir.join(resource)).unwrap())
            .unwrap();
    }
    zip.finish().unwrap();
}

fn collect_ext(dir: &Path, ext: &str, out: &mut Vec<PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_ext(&path, ext, out);
        } else if path.extension().is_some_and(|e| e == ext) {
            out.push(path);
        }
    }
}

fn collect(dir: &Path, out: &mut Vec<PathBuf>) {
    for entry in std::fs::read_dir(dir).unwrap().flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect(&path, out);
        } else {
            out.push(path);
        }
    }
}
