//! Giving the heap's free pages back to the system.

/// Gives the heap's free memory back to the system (glibc's `malloc_trim`), after a big load:
/// parsing a modded pack's tables frees gigabytes that glibc keeps in per-thread arenas, where
/// other threads can't use it. Nothing elsewhere.
pub fn trim_heap() {
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    // SAFETY: malloc_trim only releases free heap pages; no live allocation moves.
    unsafe {
        libc::malloc_trim(0);
    }
}
