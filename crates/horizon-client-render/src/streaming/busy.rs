//! What keeps streaming busy, part by part (`horizon-ctl`'s `near_loading_parts`).
use super::Streaming;

impl Streaming {
    /// What [`Self::busy`] waits for: generating, decorating, viewing, finishing, lighting,
    /// meshing, queued.
    pub fn busy_parts(&self) -> [usize; 7] {
        [
            self.generating.len(),
            self.decorating,
            self.viewing.len(),
            self.finishing.len(),
            self.lighting.len(),
            self.meshing.len(),
            self.pending(),
        ]
    }
}
