//! What the block sprites cost while they load and once loaded:
//! `texture_memory_probe RESOURCES` prints the kept memory (heap trimmed) after the assets,
//! the render assets (models and sprites), and after dropping the render assets (Linux).
use horizon_client_render::assets::{Assets, RenderAssets};
use std::path::Path;

fn kept_mb() -> u64 {
    horizon_threads::trim_heap();
    std::fs::read_to_string("/proc/self/statm")
        .ok()
        .and_then(|s| s.split_whitespace().nth(1)?.parse::<u64>().ok())
        .map_or(0, |pages| (pages * 4096) >> 20)
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = std::env::args()
        .nth(1)
        .expect("resource directory argument");
    let root = Path::new(&root);
    eprintln!(
        "a baked face is {} bytes, a baked model {} (and its faces)",
        std::mem::size_of::<horizon_client_render::models::catalog::BakedFace>(),
        std::mem::size_of::<horizon_client_render::models::catalog::BakedModel>()
    );
    let start = kept_mb();
    let assets = Assets::load(root)?;
    let after_assets = kept_mb();
    let render = RenderAssets::load(root, &assets)?;
    let after_render = kept_mb();
    let pixels = render.pixels.len() >> 20;
    let mut render = render;
    let mut last = kept_mb();
    let step = |what: &str, last: &mut u64| {
        let now = kept_mb();
        eprintln!("  dropping {what}: -{} MB", last.saturating_sub(now));
        *last = now;
    };
    render.model_catalog = Default::default();
    step("the model catalog", &mut last);
    render.model_library = Err(String::new());
    step("the item model library", &mut last);
    render.animations = Vec::new();
    step("the animations' frames", &mut last);
    render.pixels = horizon_client_render::assets::SpritePixels::new(Vec::new());
    render.pbr_normals = Vec::new();
    render.pbr_specular = Vec::new();
    step("the pixels and PBR maps", &mut last);
    drop(render);
    let after_drop = kept_mb();
    eprintln!(
        "assets +{} MB; render assets +{} MB (sprite pixels {pixels} MB); after dropping them {} MB left",
        after_assets - start,
        after_render - after_assets,
        after_drop.saturating_sub(after_assets)
    );
    Ok(())
}
