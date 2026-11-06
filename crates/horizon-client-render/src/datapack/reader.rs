//! How the generator reads a pack's files later (structure templates are read when a
//! structure places them).
use super::{DataPack, Source};
use horizon_world::worldgen::overrides;

impl DataPack {
    /// How the generator reads this pack's files later (structure templates
    /// are read when a structure places them).
    pub(super) fn reader(&self) -> overrides::PackReader {
        match &self.source {
            Source::Zip(z) => {
                let z = z.clone();
                std::sync::Arc::new(move |path: &str| z.read(path).ok())
            }
            Source::Dir(root) => {
                let root = root.clone();
                std::sync::Arc::new(move |path: &str| std::fs::read(root.join(path)).ok())
            }
        }
    }
}
