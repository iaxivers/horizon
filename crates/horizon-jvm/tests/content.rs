//! The Java mods' content (src/content.rs) for the jars in `HORIZON_TEST_MODS`: Fabric starts
//! with them on this checkout's prepared 26.3, the facts are written and every jar is carried
//! over into a folder content mod, whose blocks and items are listed.
//!
//! ```sh
//! HORIZON_TEST_MODS=/path/to/mods cargo test -p horizon-jvm --test content -- --ignored --nocapture
//! ```

use std::path::{Path, PathBuf};

#[test]
#[ignore = "needs HORIZON_TEST_MODS, the prepared game (.local/minecraft) and Java 25"]
fn java_mods_become_content_mods() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let home = horizon_jvm::find_java_home().expect("Java 25 (HORIZON_JAVA_HOME)");
    let work = root.join(".local/bridge");
    let source = PathBuf::from(std::env::var_os("HORIZON_TEST_MODS").expect("HORIZON_TEST_MODS"));
    let dir = std::env::temp_dir().join(format!("horizon-content-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let mods = dir.join("mods-java");
    std::fs::create_dir_all(&mods).unwrap();
    std::env::set_current_dir(&dir).unwrap();
    for jar in horizon_jvm::jars(&source) {
        std::fs::copy(&jar, mods.join(jar.file_name().unwrap())).unwrap();
    }
    let jars = horizon_jvm::jars(&mods);
    horizon_jvm::set_log_sink(Some(|l: &str| println!("[java] {l}")));
    let config = horizon_jvm::Config {
        home,
        work: work.clone(),
        jars: jars.clone(),
        class_path: Vec::new(),
        fabric: Some(horizon_jvm::Fabric {
            game_jar: horizon_jvm::facade::game_jar(&root).unwrap(),
            libraries: horizon_jvm::facade::game_libraries(&root).unwrap(),
            add_mods: vec![horizon_jvm::libs::ensure_fabric_api(&work.join("fabric-api")).unwrap()],
            platform: true,
            mods: mods.clone(),
            game_dir: dir.clone(),
            version: "26.3".into(),
            side: horizon_jvm::Side::Server,
        }),
    };
    horizon_jvm::start(&config).unwrap_or_else(|e| panic!("Fabric did not start: {e}"));
    let resources = root.join(".local/horizon-client/26.3");
    let cache = dir.join("content");
    let started = std::time::Instant::now();
    let folders = horizon_jvm::content::build(&jars, &resources, &cache, &|l| println!("{l}"))
        .unwrap_or_else(|e| panic!("content: {e}"));
    println!(
        "== built in {} ms: {} content mod(s)",
        started.elapsed().as_millis(),
        folders.len()
    );
    for folder in &folders {
        let blocks: Vec<String> = walk(folder, "horizon/block");
        let items: Vec<String> = walk(folder, "horizon/item");
        println!(
            "== {}: {} blocks, {} items",
            folder.display(),
            blocks.len(),
            items.len()
        );
        for b in blocks.iter().take(12) {
            let text = std::fs::read_to_string(b).unwrap();
            println!(
                "   {}: {}",
                Path::new(b).file_stem().unwrap().to_string_lossy(),
                text.replace('\n', " ")
                    .chars()
                    .take(150)
                    .collect::<String>()
            );
        }
    }
    assert_eq!(
        horizon_jvm::content::cached(&jars, &cache),
        Some(folders.clone()),
        "read back by the stamp"
    );
}

fn walk(dir: &Path, part: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        for e in std::fs::read_dir(&d).into_iter().flatten().flatten() {
            let p = e.path();
            if p.is_dir() {
                stack.push(p);
            } else if p.to_string_lossy().contains(part)
                && p.extension().is_some_and(|x| x == "json")
            {
                out.push(p.to_string_lossy().into_owned());
            }
        }
    }
    out.sort();
    out
}
