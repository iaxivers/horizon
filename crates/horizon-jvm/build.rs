//! Compiles the bridge's own Java classes (`java/`) with `javac` into `OUT_DIR`; `lib.rs`
//! embeds the class files and defines them in the JVM at start, so the game ships no jar.
//! Needs a JDK at build time (JDK 25 for Horizon; any JDK 21 or newer compiles these):
//! `JAVA_HOME`, else `javac` on the PATH.

use std::path::{Path, PathBuf};
use std::process::Command;

/// The classes `lib.rs` embeds, relative to `java/`.
const SOURCES: &[&str] = &[
    "dev/horizonlab/bridge/Natives.java",
    "dev/horizonlab/bridge/Boot.java",
];

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

fn main() {
    println!("cargo:rerun-if-changed=java");
    println!("cargo:rerun-if-env-changed=JAVA_HOME");
    let out = PathBuf::from(std::env::var_os("OUT_DIR").expect("OUT_DIR")).join("classes");
    std::fs::create_dir_all(&out).expect("create OUT_DIR/classes");
    let javac = javac();
    let status = Command::new(&javac)
        // Class files Java 21 and newer load; Horizon's players run Java 25.
        .args(["--release", "21", "-encoding", "UTF-8", "-d"])
        .arg(&out)
        .args(SOURCES.iter().map(|s| Path::new("java").join(s)))
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
}
