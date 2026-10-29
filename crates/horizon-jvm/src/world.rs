//! Horizon's blocks while Java runs: a mod that reads a block of a mirrored level
//! (`Level.getBlockState`) reads Horizon's own world, by name (`minecraft:oak_log[axis=y]`),
//! through the view the game lends for the call ([`with_blocks`]). What mods set comes back
//! as actions ([`crate::events::Action::SetBlock`]), so a read during a call never waits on
//! Horizon: the Java side keeps the call's own writes until Horizon has made them.

use std::cell::Cell;

/// Horizon's world as Java mods read it.
pub trait Blocks {
    /// The block state at `x y z` of `dimension` (`minecraft:overworld`), with its
    /// properties: `minecraft:oak_log[axis=y]`. None where no chunk is loaded.
    fn block(&self, dimension: &str, x: i32, y: i32, z: i32) -> Option<String>;
    /// The light at `x y z`: sky light in the high four bits, block light in the low. None
    /// where no chunk is loaded.
    fn light(&self, _dimension: &str, _x: i32, _y: i32, _z: i32) -> Option<u8> {
        None
    }
}

type View = *const (dyn Blocks + 'static);

thread_local! {
    /// The view of the call running on this thread (Java runs mods on the caller's thread).
    static VIEW: Cell<Option<View>> = const { Cell::new(None) };
}

/// Runs `f` (a call into Java) with `blocks` as what mods read of the world.
pub fn with_blocks<R>(blocks: &dyn Blocks, f: impl FnOnce() -> R) -> R {
    struct Reset(Option<View>);
    impl Drop for Reset {
        fn drop(&mut self) {
            VIEW.set(self.0);
        }
    }
    let view: *const (dyn Blocks + '_) = blocks;
    // SAFETY: the pointer only lives in VIEW until `_reset` drops, before `blocks` can.
    let view: View = unsafe { std::mem::transmute(view) };
    let _reset = Reset(VIEW.replace(Some(view)));
    f()
}

/// The light a mod reads, from the view of this thread's call; None outside one.
pub(crate) fn light(dimension: &str, x: i32, y: i32, z: i32) -> Option<u8> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).light(dimension, x, y, z) })
}

/// The block a mod reads, from the view of this thread's call; None outside one.
pub(crate) fn block(dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).block(dimension, x, y, z) })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Column;
    impl Blocks for Column {
        fn block(&self, _: &str, x: i32, y: i32, z: i32) -> Option<String> {
            Some(
                if x == 0 && z == 0 && y >= 64 {
                    "minecraft:oak_log[axis=y]"
                } else {
                    "minecraft:air"
                }
                .into(),
            )
        }
    }

    #[test]
    fn a_view_holds_for_its_call_only() {
        assert_eq!(block("minecraft:overworld", 0, 64, 0), None);
        let read = with_blocks(&Column, || block("minecraft:overworld", 0, 64, 0));
        assert_eq!(read.as_deref(), Some("minecraft:oak_log[axis=y]"));
        assert_eq!(block("minecraft:overworld", 0, 64, 0), None);
    }
}
