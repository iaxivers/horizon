//! Builds the bridge's own jar (`java/dev/horizonlab/bridge/`) into `OUT_DIR/horizon-bridge.jar`,
//! which `lib.rs` embeds: its classes compiled by `javac` against Fabric Loader and its
//! libraries (`fabric-libs.tsv`, downloaded once into `.local/bridge/lib/` of the checkout,
//! or `HORIZON_BRIDGE_LIBS`), plus the service file that lets Fabric Loader find
//! `HorizonGameProvider`.
//!
//! Needs a JDK at build time (JDK 25 for Horizon; any JDK 21 or newer compiles these):
//! `JAVA_HOME`, else `javac` on the PATH.

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
