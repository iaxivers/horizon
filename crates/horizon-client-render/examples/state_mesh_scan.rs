//! Meshes and lights every block state of a resource root once, alone on a stone floor:
//! `state_mesh_scan RESOURCES [FIRST_ID]` prints, per state, the quads its section meshed to,
//! and the states whose mesh or light job panics (a worker's panic loses the job: the section,
//! or the 3x3 columns around a lost light job, stop remeshing until the game restarts).
//! Lines: `<id> <state> quads=<n>`, `<id> <state> PANIC mesh|light: <message>`.
use horizon_client_render::{
    assets::Assets,
    biome::Biomes,
    blocks::BlockFacts,
    chunk::{Chunk, MIN_Y},
    graphics::LeafCullingMode,
    light::{LightTables, light_chunk},
    mesher::{MeshTables, Mesher},
    models,
};
use horizon_world::registry::BlockStateId;
use std::{panic::AssertUnwindSafe, path::Path};

fn message(e: Box<dyn std::any::Any + Send>) -> String {
    e.downcast_ref::<String>()
        .cloned()
        .or_else(|| e.downcast_ref::<&str>().map(|s| (*s).to_owned()))
        .unwrap_or_default()
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let root = args.next().expect("resource directory argument");
    let first: u32 = args.next().map_or(Ok(0), |a| a.parse())?;
    let root = Path::new(&root);
    let assets = Assets::load(root)?;
    let render = assets.reload(root)?;
    let facts = BlockFacts::load(root, assets.registry)?;
    let biomes = Biomes::load(root)?;
    let visibility = models::load_visibility(root, assets.registry)?;
    let tables = MeshTables::new(&assets, &render, &facts, visibility)?;
    let light = LightTables::load(root, assets.registry, &facts)?;
    let stone = assets
        .registry
        .default_state("minecraft:stone")
        .ok_or("no stone")?;
    std::panic::set_hook(Box::new(|_| {}));
    let y = 64;
    let sy = (y - MIN_Y) / 16;
    for id in first..assets.registry.len() as u32 {
        let name = assets
            .registry
            .state(BlockStateId(id))
            .map(|s| format!("{}{:?}", s.name, s.properties))
            .unwrap_or_default();
        let mut chunks: Vec<Chunk> = (0..9)
            .map(|i| {
                let mut c = Chunk::empty(i % 3 - 1, i / 3 - 1);
                for x in 0..16 {
                    for z in 0..16 {
                        c.set(x, y - 1, z, stone.0);
                    }
                }
                c
            })
            .collect();
        // In the middle and on the chunk's corner (neighbours' sides).
        chunks[4].set(8, y, 8, id);
        chunks[4].set(0, y, 0, id);
        let region: [[Option<&Chunk>; 3]; 3] =
            std::array::from_fn(|z| std::array::from_fn(|x| Some(&chunks[z * 3 + x])));
        let lit = std::panic::catch_unwind(AssertUnwindSafe(|| light_chunk(&region, &light, true)));
        if let Err(e) = lit {
            println!("{id} {name} PANIC light: {}", message(e));
            continue;
        }
        let meshed = std::panic::catch_unwind(AssertUnwindSafe(|| {
            let mut mesher = Mesher {
                nether_shading: false,
                shader_pack_lighting: false,
                shader_pack: false,
                separate_ao: false,
                assets: &assets,
                render: &render,
                tables: &tables,
                biomes: &biomes,
                visibility: tables.visibility.clone(),
                zoom: horizon_core::biome::BiomeZoom::new(0),
                blend_radius: 2,
                smooth_lighting: true,
                cutout_leaves: true,
                translucent_layers: &[],
                leaf_culling: LeafCullingMode::Off,
            };
            let mesh = mesher.mesh(&region, sy);
            (mesh.solid.len() + mesh.translucent.len()) / 4
        }));
        match meshed {
            Ok(quads) => println!("{id} {name} quads={quads}"),
            Err(e) => println!("{id} {name} PANIC mesh: {}", message(e)),
        }
    }
    Ok(())
}
