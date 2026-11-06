//! The chunk at NoiseBasedChunkGenerator.doFill's end (noise, aquifers, ore veins), for
//! another generator's surface, carvers and features.
use super::*;

impl GeneratorWorker {
    /// The chunk at doFill's end (noise, aquifers, ore veins), for another generator's
    /// surface, carvers and features.
    pub fn generate_noise(&mut self, pos: ChunkPos) -> Result<ProtoChunk, GeneratorError> {
        self.pipeline.generate_noise(&mut self.state, pos)
    }
}

impl<R: RandomSource + 'static> Pipeline<R>
where
    R::Positional: Clone + Send + Sync,
{
    /// [`SeededPipeline::generate_noise`].
    pub(super) fn noise_only(
        &self,
        worker: &mut WorkerState,
        pos: ChunkPos,
    ) -> Result<ProtoChunk, GeneratorError> {
        let mut context = std::mem::take(&mut worker.terrain_context);
        let result = self.generate_with_context(worker, pos, &mut context, true);
        context.reset();
        worker.terrain_context = context;
        result
    }
}

impl ProtoChunk {
    /// The proto chunk `generate_with_context` returns with `noise_only`.
    pub(super) fn at_noise(
        terrain: TerrainChunk<'static, GenBlockStates>,
        biomes: BiomeChunk<BiomeId>,
        data: Arc<GeneratorData>,
        structure_starts: Vec<Arc<super::super::structure::start::StructureStart>>,
        own_starts: Starts,
    ) -> Self {
        ProtoChunk {
            terrain,
            biomes,
            data,
            decorated: false,
            block_entities: Vec::new(),
            structure_starts,
            own_starts,
            entities: Vec::new(),
        }
    }
}
