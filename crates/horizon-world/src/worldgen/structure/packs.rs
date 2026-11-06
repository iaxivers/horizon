//! Data packs' structure templates over the game's own.
use super::*;

impl StructureResources {
    /// The data packs' templates over the game's own: a pack's file for an
    /// id is read in place of the prepared one (a mod replacing vanilla's
    /// ancient city centres), and the packs' new ids are found.
    pub fn with_packs(mut self, packs: crate::worldgen::overrides::PackTemplates) -> Self {
        self.packs = packs;
        self
    }
}
