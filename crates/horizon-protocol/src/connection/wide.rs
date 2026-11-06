//! An in-memory pipe's frames, which may pass the network's 21-bit limit.
use super::*;

impl ConnReader {
    /// The next frame's (prefix, length): an in-memory pipe's frames may pass the network's
    /// 21-bit limit ([`frame::unbounded_contents`]).
    pub(super) fn header(&self) -> Result<Option<(usize, usize)>> {
        let pending = &self.buffer[self.start..self.end];
        if self.wide {
            frame::wide_header(pending)
        } else {
            frame::header(pending)
        }
    }
}

impl ConnWriter {
    /// What [`Self::send`] frames for `body` (no adapter): a packet over an in-memory pipe
    /// isn't held to the network's 21-bit frame (a big pack's join sends packets past
    /// 2 MiB to the window's own client).
    pub(super) fn contents(&self, body: &[u8]) -> Result<Vec<u8>> {
        if self.wide {
            frame::unbounded_contents(body, self.threshold)
        } else {
            frame::contents(body, self.threshold)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The window's own connection carries a packet past the network's 2 MiB frame
    /// (a big pack's join: 2.3 MB), whole; a socket's still refuses one.
    #[test]
    fn a_local_pipe_carries_packets_past_the_frame_limit() {
        let (a, b) = Pipe::pair();
        let mut body = vec![7];
        body.extend((0..3_000_000u32).map(|i| (i % 251) as u8));
        let sent = body.clone();
        let server = std::thread::spawn(move || {
            let mut c = Connection::local(b);
            c.send(&sent).unwrap();
        });
        let mut c = Connection::local(a);
        let (id, payload) = c.recv().unwrap();
        assert_eq!(id, 7);
        assert_eq!(payload, body[1..]);
        server.join().unwrap();
        assert!(frame::contents(&body, None).is_err());
    }
}
