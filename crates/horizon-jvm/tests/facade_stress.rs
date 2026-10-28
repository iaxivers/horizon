//! Builds the facade of any jar and loads, verifies and initializes every class in it.
//! Run it on the real game jar to check the facade before Fabric uses it:
//!
//! ```sh
//! HORIZON_FACADE_JAR=~/.minecraft/versions/26.3/26.3.jar \
//!   cargo test -p horizon-jvm --test facade_stress -- --ignored --nocapture
//! ```
//!
//! `VerifyError` and `ClassFormatError` are the facade's bugs and fail the test; a class that
//! needs a library the jar doesn't hold (`NoClassDefFoundError`) is counted, not failed.

use jni::objects::{JString, JValue};
use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Path, PathBuf};

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
#[ignore = "needs HORIZON_FACADE_JAR: a jar to build the facade of"]
fn every_class_of_a_jar_loads() {
    let jar = PathBuf::from(std::env::var_os("HORIZON_FACADE_JAR").expect("HORIZON_FACADE_JAR"));
    let home = java_home().expect("a Java home (HORIZON_JAVA_HOME)");
    let dir = std::env::temp_dir().join(format!("horizon-facade-stress-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let facade = dir.join("facade.jar");
    let started = std::time::Instant::now();
    let stats = horizon_jvm::facade::build(std::slice::from_ref(&jar), &facade).unwrap();
    println!(
        "facade: {} classes, {} bodies replaced, {} skipped, {} ms, {} -> {} bytes",
        stats.classes,
        stats.stubbed,
        stats.skipped,
        started.elapsed().as_millis(),
        std::fs::metadata(&jar).unwrap().len(),
        std::fs::metadata(&facade).unwrap().len()
    );

    let mut names = Vec::new();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&facade).unwrap()).unwrap();
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).unwrap();
        let mut skip = Vec::new();
        entry.read_to_end(&mut skip).unwrap();
        if let Some(class) = entry.name().strip_suffix(".class") {
            names.push(class.replace('/', "."));
        }
    }
    names.sort();

    let bridge = horizon_jvm::start(&home, &[], &[facade]).expect("JVM starts");
    let mut env = bridge.vm().attach_current_thread().unwrap();
    let mut failures: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut loaded = 0;
    for name in &names {
        let jname = env.new_string(name).unwrap();
        let result = env.call_static_method(
            "java/lang/Class",
            "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&jname)],
        );
        if result.is_ok() {
            loaded += 1;
            continue;
        }
        let throwable = env.exception_occurred().unwrap();
        env.exception_clear().unwrap();
        let text = env
            .call_method(&throwable, "toString", "()Ljava/lang/String;", &[])
            .and_then(|v| v.l())
            .map(|s| String::from(env.get_string(&JString::from(s)).unwrap()))
            .unwrap_or_else(|_| "?".into());
        let kind = text.split(':').next().unwrap_or("?").to_string();
        failures
            .entry(kind)
            .or_default()
            .push(format!("{name}: {text}"));
        env.delete_local_ref(throwable).ok();
        env.delete_local_ref(jname).ok();
    }
    println!("loaded {loaded} of {}", names.len());
    let mut bad = 0;
    for (kind, list) in &failures {
        println!("{kind}: {}", list.len());
        for line in list.iter().take(5) {
            println!("  {line}");
        }
        if kind.contains("VerifyError") || kind.contains("ClassFormatError") {
            bad += list.len();
        }
    }
    std::fs::remove_dir_all(&dir).ok();
    assert_eq!(bad, 0, "the facade made classes the JVM refuses");
}
