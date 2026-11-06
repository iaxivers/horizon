//! The riding jump's charge (`LocalPlayer.aiStep`), for vanilla's mounts.
use super::*;

impl MountState {
    /// `LocalPlayer.aiStep`'s riding jump: the jump key charges `jump_scale`, its release
    /// jumps (`getPlayerJumpPendingScale` returned, 0 otherwise) and ten ticks cool down.
    pub(super) fn charge_jump(&mut self, jump_key: bool) -> f32 {
        let mut jump = 0.0;
        let st = self;
        if st.jump_ticks < 0 {
            st.jump_ticks += 1;
            if st.jump_ticks == 0 {
                st.jump_scale = 0.0;
            }
        }
        if st.was_jumping && !jump_key {
            st.jump_ticks = -10;
            let amount = (st.jump_scale * 100.0).floor() as i32;
            // PlayerRideableJumping.getPlayerJumpPendingScale, the scale a
            // guest's START_RIDING_JUMP carries as `data`.
            jump = crate::mobs::equine::jump_pending_scale(amount);
        } else if !st.was_jumping && jump_key {
            st.jump_ticks = 0;
            st.jump_scale = 0.0;
        } else if st.was_jumping {
            st.jump_ticks += 1;
            st.jump_scale = if st.jump_ticks < 10 {
                st.jump_ticks as f32 * 0.1
            } else {
                0.8 + 2.0 / (st.jump_ticks - 9) as f32 * 0.1
            };
        }
        st.was_jumping = jump_key;
        jump
    }
}
