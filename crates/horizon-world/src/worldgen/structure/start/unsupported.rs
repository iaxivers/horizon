//! The structures a dimension's sets may place that Horizon can't generate.
use super::*;

impl StructureRegistry {
    /// The structures the sets `sets` (indices) may place that Horizon can't generate: a
    /// structure type of a mod's own (Friends & Foes' citadel), or a set placed in a way
    /// Horizon doesn't know. (structure, its type or its set's placement), once each, in
    /// the sets' order.
    pub fn unsupported(&self, sets: &[usize]) -> Vec<(String, String)> {
        let mut out: Vec<(String, String)> = Vec::new();
        for set in sets.iter().filter_map(|&i| self.sets.get(i)) {
            for &(index, _) in &set.structures {
                let def = &self.structures[index];
                let why = match &def.kind {
                    StructureKind::Unsupported(kind) => kind.clone(),
                    _ if matches!(set.placement, Placement::Unsupported) => {
                        format!("{} placement", set.name)
                    }
                    _ => continue,
                };
                if !out.iter().any(|(name, _)| *name == def.name) {
                    out.push((def.name.clone(), why));
                }
            }
        }
        out
    }
}
