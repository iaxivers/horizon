//! Noises a mod seeds under another identifier than their own.
use super::*;

impl DensityGraph {
    /// `register_noise` for a noise a mod seeds under another identifier than its own
    /// (Tectonic seeds `tectonic:parameter/<x>` as `minecraft:<x>`): `seed` is hashed in
    /// place of `name` when the noise is made.
    pub fn register_noise_seeded(
        &mut self,
        name: String,
        seed: Option<String>,
        definition: crate::noise::NormalNoise,
    ) -> Result<NoiseId, CompileError> {
        let id = self.register_noise(name, definition)?;
        self.noises[id.0].seed = seed;
        Ok(id)
    }
}

impl seeded::NoiseEntry {
    /// The identifier the noise's random is hashed from.
    pub(super) fn seed(&self) -> &str {
        self.seed.as_deref().unwrap_or(&self.name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_noise_seeded_under_another_id_is_that_id_s_noise() {
        // Tectonic seeds tectonic:parameter/continentalness as minecraft:continentalness.
        let mut builder = crate::noise::NormalNoise::builder();
        builder.set_base_octave(-9).set_octave_count(4).unwrap();
        let definition = builder.build().unwrap();
        let mut graph = DensityGraph::new();
        let own = graph
            .register_noise("minecraft:continentalness".to_owned(), definition.clone())
            .unwrap();
        let aliased = graph
            .register_noise_seeded(
                "tectonic:parameter/continentalness".to_owned(),
                Some("minecraft:continentalness".to_owned()),
                definition.clone(),
            )
            .unwrap();
        let plain = graph
            .register_noise("tectonic:other".to_owned(), definition)
            .unwrap();
        let compiler = DensityCompiler::new_seeded(graph, 1234, false);
        let at = |id| {
            compiler
                .get_noise_sampler(id)
                .unwrap()
                .get3(301.5, 0.0, -77.25)
        };
        assert_eq!(at(own).to_bits(), at(aliased).to_bits());
        assert_ne!(at(own).to_bits(), at(plain).to_bits());
    }
}
