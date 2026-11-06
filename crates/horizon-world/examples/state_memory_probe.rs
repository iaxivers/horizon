//! How much resident memory each per-state table of a content root takes:
//! `state_memory_probe RESOURCES` prints RSS after each load (Linux).
use horizon_world::level::{Behaviours, data::load_registry};
use std::path::Path;

fn rss_mb() -> u64 {
    std::fs::read_to_string("/proc/self/statm")
        .ok()
        .and_then(|s| s.split_whitespace().nth(1)?.parse::<u64>().ok())
        .map_or(0, |pages| pages * 4096 / (1 << 20))
}

unsafe extern "C" {
    fn malloc_trim(pad: usize) -> i32;
}

/// RSS after giving freed memory back (what the table keeps, not its parse).
fn kept_mb() -> u64 {
    // SAFETY: malloc_trim only returns free heap pages to the system.
    unsafe { malloc_trim(0) };
    rss_mb()
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = std::env::args()
        .nth(1)
        .expect("resource directory argument");
    let root = Path::new(&root);
    let base = rss_mb();
    let mark = |what: &str, before: &mut u64| {
        let peak = rss_mb();
        let now = kept_mb();
        eprintln!(
            "{what:<28} kept +{:>5} MB  (rss before trim {peak} MB, after {now} MB)",
            now.saturating_sub(*before)
        );
        *before = now;
    };
    let mut last = base;
    let registry: &'static _ = Box::leak(Box::new(load_registry(root)?));
    mark(&format!("registry ({} states)", registry.len()), &mut last);
    let behaviours = Behaviours::load(registry, root)?;
    mark("level::Behaviours", &mut last);
    let data =
        horizon_world::worldgen::generator::GeneratorData::load(&root.join("worldgen"), registry)?;
    mark("GeneratorData", &mut last);
    let overworld = horizon_world::worldgen::generator::OverworldGenerator::for_world(
        data.clone(),
        1234,
        "minecraft:normal",
        "minecraft:overworld",
        true,
    )?;
    mark("OverworldGenerator", &mut last);
    std::hint::black_box((&behaviours, &data, &overworld));
    Ok(())
}
