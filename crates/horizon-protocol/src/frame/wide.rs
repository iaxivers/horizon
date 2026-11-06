//! Frames of an in-memory connection, which may pass the network's 21 bits.
use crate::{Error, Result};

/// [`header`](super::header) for an in-memory connection: a length of up to five VarInt bytes
/// (frames made by [`unbounded_contents`](super::unbounded_contents)).
pub fn wide_header(bytes: &[u8]) -> Result<Option<(usize, usize)>> {
    let mut len = 0usize;
    for i in 0..5 {
        let Some(&b) = bytes.get(i) else {
            return Ok(None);
        };
        len |= usize::from(b & 0x7F) << (7 * i);
        if b & 0x80 == 0 {
            if len == 0 {
                return Err(Error::new("Frame length cannot be zero"));
            }
            return Ok(Some((i + 1, len)));
        }
    }
    Err(Error::new("length wider than 32 bits"))
}
