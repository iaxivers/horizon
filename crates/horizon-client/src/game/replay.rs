//! `--replay FILE`: a ReplayMod recording (`.mcpr`) watched at start, by Horizon's replay
//! viewer (`lan::replay`) joined as a server.
use super::*;

impl Game<'_> {
    /// Joins the replay viewer playing `--replay`'s recording.
    pub(super) fn open_replay(&mut self) -> Result<()> {
        let path = self.options.replay.clone().unwrap_or_default();
        let name = self.options.player_name();
        match horizon_client::lan::replay::open(&path) {
            Ok(pipe) => self.connect_local(pipe, &name)?,
            Err(e) => {
                log::warn!(
                    "Horizon: the replay {} couldn't be opened: {e}",
                    path.display()
                );
                return Err(e.into());
            }
        }
        Ok(())
    }
}
