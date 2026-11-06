//! Another generator's say in a dimension Horizon generates (a mod engine's): its
//! biomes, spawn and structures, and the noise fill it finishes.
use super::*;

/// Another generator's say in a dimension ([`BiomeOracle`]).
#[derive(Default)]
pub(super) struct Oracle {
    /// Where this dimension's biomes really come from, when another generator makes it:
    /// biome queries of places not generated yet ask it first.
    biomes: std::sync::OnceLock<Arc<dyn BiomeOracle>>,
    /// Another generator may take this dimension over ([`VanillaTerrain::expect_oracle`]) and
    /// hasn't said yet whether it does: the spawn search waits.
    pending: std::sync::atomic::AtomicBool,
}
impl Oracle {
    fn get(&self) -> Option<&Arc<dyn BiomeOracle>> {
        self.biomes.get()
    }
    /// [`BiomeOracle::find_closest_biome`], if there is an oracle and it answers.
    pub(super) fn find_closest_biome(
        &self,
        search: &horizon_world::worldgen::generator::BiomeSearch<'_>,
    ) -> Option<Option<(String, [i32; 3])>> {
        self.get().and_then(|o| o.find_closest_biome(search))
    }
    /// [`BiomeOracle::noise_biomes`], if there is an oracle and it answers.
    pub(super) fn noise_biomes(&self, quarts: &[[i32; 3]]) -> Option<Vec<Option<String>>> {
        self.get().and_then(|o| o.noise_biomes(quarts))
    }
}

/// A chunk's blocks after the noise fill ([`VanillaTerrain::noise_fill`]): `blocks[i]` (`i` =
/// `((y - min_y) * 16 + z) * 16 + x`) indexes `palette` (`minecraft:water[level=0]`);
/// `postprocess` holds x y z triples of fluids the aquifer marked for an update.
pub struct NoiseFill {
    pub min_y: i32,
    pub palette: Vec<String>,
    pub blocks: Vec<i32>,
    pub postprocess: Vec<i32>,
}
/// A dimension's biomes as another generator makes them (a mod's biome source).
pub trait BiomeOracle: Send + Sync {
    /// `BiomeSource.getNoiseBiome` at each quart position; None to use Horizon's own.
    fn noise_biomes(&self, quarts: &[[i32; 3]]) -> Option<Vec<Option<String>>>;
    /// ChunkGenerator.getOrigin's chunk and getSpawnHeight; None to use Horizon's own.
    fn spawn_origin(&self) -> Option<((i32, i32), i32)> {
        None
    }
    /// `/locate structure` of `selector` (an id or a `#tag`) from `from` within `radius`
    /// chunks (ChunkGenerator.findNearestMapStructure); None to use Horizon's own search.
    fn locate_structure(
        &self,
        _selector: &str,
        _from: [i32; 3],
        _radius: i32,
    ) -> Option<Option<(String, [i32; 3])>> {
        None
    }
    /// `BiomeSource.findClosestBiome3d`; None to use Horizon's own search.
    fn find_closest_biome(
        &self,
        search: &horizon_world::worldgen::generator::BiomeSearch<'_>,
    ) -> Option<Option<(String, [i32; 3])>>;
}
/// Logs the structures a dimension's sets may place that Horizon's generator can't.
pub(super) fn warn_unsupported_structures(dimension: &str, structures: &[(String, String)]) {
    if !structures.is_empty() {
        log::warn!(
            "Horizon: {dimension}: {} structures can't generate here (types Horizon lacks): {}",
            structures.len(),
            structures
                .iter()
                .map(|(name, kind)| format!("{name} ({kind})"))
                .collect::<Vec<_>>()
                .join(", ")
        );
    }
}

impl VanillaTerrain {
    /// The structures this dimension's sets may place that Horizon's generator can't
    /// (structure, its type): a mod's own structure type.
    pub fn unsupported_structures(&self) -> Vec<(String, String)> {
        self.generator.unsupported_structures()
    }
    /// Chunk `x z` at NoiseBasedChunkGenerator.doFill's end (noise, aquifers, ore veins:
    /// no surface, carvers or features), for another generator to finish. None if it failed.
    pub fn noise_chunk(&self, worker: &mut GeneratorWorker, x: i32, z: i32) -> Option<ProtoChunk> {
        match worker.generate_noise(ChunkPos::new(x, z)) {
            Ok(chunk) => Some(chunk),
            Err(e) => {
                log::warn!("Horizon: noise of chunk {x} {z} failed: {e}");
                None
            }
        }
    }
    /// [`VanillaTerrain::noise_chunk`]'s blocks as a [`NoiseFill`].
    pub fn noise_fill_of(&self, chunk: &ProtoChunk, x: i32, z: i32) -> NoiseFill {
        let terrain = &chunk.terrain;
        let registry = self.generator.data().registry();
        let (min_y, height) = (terrain.min_y(), terrain.height());
        let mut palette: Vec<String> = Vec::new();
        let mut index: std::collections::HashMap<u32, i32> = std::collections::HashMap::new();
        let mut blocks = vec![0i32; (height as usize) * 256];
        for (i, slot) in blocks.iter_mut().enumerate() {
            let (bx, bz, y) = (
                (i & 15) as i32,
                ((i >> 4) & 15) as i32,
                min_y + (i >> 8) as i32,
            );
            let state = terrain.block(x * 16 + bx, y, z * 16 + bz);
            *slot = *index.entry(state.0).or_insert_with(|| {
                palette.push(registry.state(state).map_or_else(
                    || "minecraft:air".to_owned(),
                    |s| {
                        if s.properties.is_empty() {
                            s.name.clone()
                        } else {
                            let props: Vec<String> = s
                                .properties
                                .iter()
                                .map(|(k, v)| format!("{k}={v}"))
                                .collect();
                            format!("{}[{}]", s.name, props.join(","))
                        }
                    },
                ));
                palette.len() as i32 - 1
            });
        }
        let mut postprocess = Vec::new();
        let base_section = min_y >> 4;
        for (i, marks) in terrain.postprocessing().iter().enumerate() {
            let sy = (base_section + i as i32) * 16;
            for &packed in marks {
                let p = i32::from(packed);
                postprocess.extend([
                    x * 16 + (p & 15),
                    sy + ((p >> 4) & 15),
                    z * 16 + ((p >> 8) & 15),
                ]);
            }
        }
        NoiseFill {
            min_y,
            palette,
            blocks,
            postprocess,
        }
    }
    /// `/locate structure`: the oracle's when another generator makes the dimension (its
    /// structures follow its biomes, and mods add their own), else [`VanillaTerrain::locate`].
    pub fn locate_structure(
        &self,
        selector: &str,
        names: &[String],
        from: [i32; 3],
        radius: i32,
    ) -> Option<(String, [i32; 3])> {
        if let Some(found) = self
            .oracle
            .get()
            .and_then(|o| o.locate_structure(selector, from, radius))
        {
            return found;
        }
        self.locate(names, from, radius)
    }
    /// Makes `oracle` the source of this dimension's biome queries (once).
    pub fn set_biome_oracle(&self, oracle: Arc<dyn BiomeOracle>) {
        let _ = self.oracle.biomes.set(oracle);
        self.no_oracle();
    }
    /// An oracle may come ([`Self::set_biome_oracle`]) or not ([`Self::no_oracle`]): until
    /// then [`Self::oracle_decided`] is false.
    pub fn expect_oracle(&self) {
        if self.oracle.biomes.get().is_none() {
            self.oracle
                .pending
                .store(true, std::sync::atomic::Ordering::Release);
        }
    }
    /// No oracle comes: Horizon's generator is this dimension's.
    pub fn no_oracle(&self) {
        self.oracle
            .pending
            .store(false, std::sync::atomic::Ordering::Release);
    }
    /// Whether it is known which generator makes this dimension.
    pub fn oracle_decided(&self) -> bool {
        !self
            .oracle
            .pending
            .load(std::sync::atomic::Ordering::Acquire)
    }
    /// ChunkGenerator.getOrigin and getSpawnHeight: the other generator's when it has its
    /// own (a mod's world type), else Horizon's origin at height 64.
    pub fn spawn_origin(&self) -> crate::Result<((i32, i32), i32)> {
        if let Some(found) = self.oracle.get().and_then(|o| o.spawn_origin()) {
            return Ok(found);
        }
        let origin = self.origin()?;
        Ok(((origin.x, origin.z), 64))
    }
}
