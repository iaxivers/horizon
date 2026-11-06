//! Precipitation at a column whose answers (top, biome) the caller has.
use super::*;

impl Behaviours {
    /// `ServerLevel.tickPrecipitation` at a column whose top and biome answers the caller
    /// has (another engine's seasons, say, answer them).
    pub fn precipitation_tick_with<L: LevelAccess + ?Sized>(
        &self,
        level: &mut L,
        ticks: &mut Ticks,
        info: Precipitation,
    ) {
        self.sim(level, ticks).precipitation_tick(info);
    }
}
