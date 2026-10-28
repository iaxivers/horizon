//! Starts a real JVM and checks both directions of the bridge: Rust calls `Boot.hello`,
//! and Java calls the Rust native `Natives.log`. One test only: a process gets one JVM.
//!
//! Uses `HORIZON_JAVA_HOME`, else `JAVA_HOME`, else the `java` on the PATH; with none of
//! them it says so and passes.

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
        .map(|dir| dir.join(if cfg!(windows) { "java.exe" } else { "java" }))
        .find(|java| java.is_file())
        .and_then(|java| std::fs::canonicalize(java).ok())
        .and_then(|java| java.parent()?.parent().map(Path::to_path_buf))
}

#[test]
fn jvm_starts_and_both_directions_work() {
    let Some(home) = java_home() else {
        eprintln!("no Java found: set HORIZON_JAVA_HOME to run this test");
        return;
    };
    let dir = std::env::temp_dir().join(format!("horizon-jvm-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("b.jar"), b"").unwrap();
    std::fs::write(dir.join("a.JAR"), b"").unwrap();
    std::fs::write(dir.join("notes.txt"), b"").unwrap();
    let jars = horizon_jvm::jars(&dir);
    assert_eq!(jars.len(), 2, "only .jar files, any case: {jars:?}");
    assert!(
        jars[0].ends_with("a.JAR") && jars[1].ends_with("b.jar"),
        "sorted: {jars:?}"
    );
    assert!(horizon_jvm::jars(&dir.join("missing")).is_empty());

    horizon_jvm::set_log_sink(Some(record));
    let bridge = horizon_jvm::start(&home, &jars).expect("JVM starts");
    let version = bridge.java_version().to_string();
    assert!(!version.is_empty());

    // A second start returns the same JVM and runs nothing again.
    let again = horizon_jvm::start(&home, &[]).expect("idempotent");
    assert!(std::ptr::eq(bridge, again));
    assert!(horizon_jvm::get().is_some());

    let lines = LINES.lock().unwrap().clone();
    assert_eq!(
        lines[0],
        format!("Hello from Java {version} inside Horizon"),
        "{lines:?}"
    );
    assert_eq!(lines[1], "2 jars in mods-java/");
    assert_eq!(
        lines.len(),
        4,
        "hello once, then one line per jar: {lines:?}"
    );
    std::fs::remove_dir_all(&dir).ok();
}
