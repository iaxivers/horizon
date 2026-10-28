//! Builds a facade from a small stand-in game (`java/fixtures/`, compiled here with javac) and
//! loads it in a real JVM: every class must load and verify, constructors must run, and every
//! method must return its type's default instead of what the original computed.
//!
//! Needs a JDK (`HORIZON_JAVA_HOME`, `JAVA_HOME` or `javac` on the PATH); without one it says
//! so and passes.

use jni::objects::JValue;
use std::io::Write;
use std::path::{Path, PathBuf};

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

/// Compiles the fixtures and packs them as `game.jar`.
fn game_jar(home: &Path, dir: &Path) -> PathBuf {
    let sources =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("java/fixtures/dev/horizonlab/fixture");
    let classes = dir.join("classes");
    let javac = home
        .join("bin")
        .join(if cfg!(windows) { "javac.exe" } else { "javac" });
    let mut files: Vec<PathBuf> = std::fs::read_dir(&sources)
        .unwrap()
        .flatten()
        .map(|e| e.path())
        .collect();
    files.sort();
    let status = std::process::Command::new(javac)
        .args(["--release", "21", "-d"])
        .arg(&classes)
        .args(&files)
        .status()
        .unwrap();
    assert!(status.success());
    let jar = dir.join("game.jar");
    let mut zip = zip::ZipWriter::new(std::fs::File::create(&jar).unwrap());
    let options = zip::write::SimpleFileOptions::default();
    let package = classes.join("dev/horizonlab/fixture");
    let mut names: Vec<_> = std::fs::read_dir(&package)
        .unwrap()
        .flatten()
        .map(|e| e.path())
        .collect();
    names.sort();
    for class in names {
        let name = format!(
            "dev/horizonlab/fixture/{}",
            class.file_name().unwrap().to_string_lossy()
        );
        zip.start_file(name, options).unwrap();
        zip.write_all(&std::fs::read(&class).unwrap()).unwrap();
    }
    zip.start_file("assets/minecraft/lang/en_us.json", options)
        .unwrap();
    zip.write_all(b"{}").unwrap();
    zip.finish().unwrap();
    jar
}

#[test]
fn facade_classes_load_and_return_defaults() {
    let Some(home) = java_home() else {
        eprintln!("no JDK found: set HORIZON_JAVA_HOME to run this test");
        return;
    };
    let dir = std::env::temp_dir().join(format!("horizon-facade-test-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let game = game_jar(&home, &dir);
    let facade = dir.join("facade.jar");
    // The game jar twice: the second copy of every class is skipped.
    let stats = horizon_jvm::facade::build(&[game.clone(), game], &facade).unwrap();
    assert_eq!(stats.classes, 5, "Base, Shape, Sub, Kind, Point: {stats:?}");
    assert_eq!(stats.skipped, 5);
    assert!(stats.stubbed >= 20, "{stats:?}");

    let bridge = horizon_jvm::start(&home, &[], &[facade]).expect("JVM starts");
    let mut env = bridge.vm().attach_current_thread().unwrap();
    let mut run = || -> jni::errors::Result<()> {
        // Constructors: one with a `new` among the super call's arguments, one calling this(...).
        let sub = env.new_object("dev/horizonlab/fixture/Sub", "(I)V", &[JValue::Int(5)])?;
        let sub2 = env.new_object("dev/horizonlab/fixture/Sub", "()V", &[])?;
        assert!(!sub2.is_null());
        assert_eq!(env.call_method(&sub, "answer", "()I", &[])?.i()?, 0);
        assert!(
            env.call_method(&sub, "name", "()Ljava/lang/String;", &[])?
                .l()?
                .is_null()
        );
        let big = env.call_method(
            &sub,
            "big",
            "(JD)J",
            &[JValue::Long(1), JValue::Double(2.0)],
        )?;
        assert_eq!(big.j()?, 0);
        // Interface default method, static methods, static initializers.
        assert_eq!(env.call_method(&sub, "area", "()F", &[])?.f()?, 0.0);
        let pi = env.call_static_method("dev/horizonlab/fixture/Base", "pi", "()D", &[])?;
        assert_eq!(pi.d()?, 0.0);
        let unit = env.call_static_method(
            "dev/horizonlab/fixture/Shape",
            "unit",
            "()Ldev/horizonlab/fixture/Shape;",
            &[],
        )?;
        assert!(unit.l()?.is_null());
        let counter = env.get_static_field("dev/horizonlab/fixture/Sub", "counter", "I")?;
        assert_eq!(counter.i()?, 0, "the static initializer is a stub");
        // Enums and records keep what makes them enums and records.
        let stone = env.get_static_field(
            "dev/horizonlab/fixture/Kind",
            "STONE",
            "Ldev/horizonlab/fixture/Kind;",
        )?;
        assert!(stone.l()?.is_null());
        let kind = env.find_class("dev/horizonlab/fixture/Kind")?;
        assert!(env.call_method(&kind, "isEnum", "()Z", &[])?.z()?);
        let point = env.new_object(
            "dev/horizonlab/fixture/Point",
            "(II)V",
            &[JValue::Int(2), JValue::Int(3)],
        )?;
        assert_eq!(env.call_method(&point, "sum", "()I", &[])?.i()?, 0);
        let point_class = env.find_class("dev/horizonlab/fixture/Point")?;
        assert!(env.call_method(&point_class, "isRecord", "()Z", &[])?.z()?);
        Ok(())
    };
    if let Err(e) = run() {
        if env.exception_check().unwrap_or(false) {
            env.exception_describe().ok();
        }
        panic!("{e}");
    }
    std::fs::remove_dir_all(&dir).ok();
}
