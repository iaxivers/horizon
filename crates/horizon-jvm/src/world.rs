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
    /// The block entity at `x y z` as vanilla saves it (`saveWithFullMetadata`), in SNBT:
    /// a chest's items, for a mod that reads them. None where there is none.
    fn block_entity(&self, _dimension: &str, _x: i32, _y: i32, _z: i32) -> Option<String> {
        None
    }
    /// The blocks of chunk `cx cz` from `min_y` up `height` blocks, at once: `out[i]` (`i` =
    /// `((y - min_y) * 16 + z) * 16 + x`) is an index into the returned palette of states
    /// (`minecraft:oak_log[axis=y]`). None where the chunk isn't loaded.
    fn column(
        &self,
        _dimension: &str,
        _cx: i32,
        _cz: i32,
        _min_y: i32,
        _out: &mut [i32],
    ) -> Option<Vec<String>> {
        None
    }
    /// The biomes of chunk `cx cz` as [`Blocks::column`] gives its blocks, one a quart (4x4x4
    /// blocks; `i` = `((qy - min_y / 4) * 4 + qz) * 4 + qx`), with a palette of biome ids.
    fn column_biomes(
        &self,
        _dimension: &str,
        _cx: i32,
        _cz: i32,
        _min_y: i32,
        _out: &mut [i32],
    ) -> Option<Vec<String>> {
        None
    }
}

/// Counts the views lent ([`with_blocks`]): what Java read of one may not hold in the next.
static EPOCH: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);

/// The number of the view lent last (Java's chunk copies are good for one view only).
pub(crate) fn epoch() -> i64 {
    EPOCH.load(std::sync::atomic::Ordering::SeqCst)
}

/// [`Blocks::column`] of this thread's view; None outside one.
pub(crate) fn column(
    dimension: &str,
    cx: i32,
    cz: i32,
    min_y: i32,
    out: &mut [i32],
) -> Option<Vec<String>> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).column(dimension, cx, cz, min_y, out) })
}

/// [`Blocks::column_biomes`] of this thread's view; None outside one.
pub(crate) fn column_biomes(
    dimension: &str,
    cx: i32,
    cz: i32,
    min_y: i32,
    out: &mut [i32],
) -> Option<Vec<String>> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).column_biomes(dimension, cx, cz, min_y, out) })
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
    EPOCH.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    f()
}

/// The light a mod reads, from the view of this thread's call; None outside one.
pub(crate) fn light(dimension: &str, x: i32, y: i32, z: i32) -> Option<u8> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).light(dimension, x, y, z) })
}

/// The block entity a mod reads, from the view of this thread's call; None outside one.
pub(crate) fn block_entity(dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).block_entity(dimension, x, y, z) })
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
