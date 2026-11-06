//! `HORIZON_MEM_LOG=1`: the memory the game keeps after each load stage, logged (its heap
//! trimmed first), to see what a pack's block states cost where.

/// Logs the resident memory after `stage` when `HORIZON_MEM_LOG` is set.
pub fn mark(stage: &str) {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    if !*ON.get_or_init(|| std::env::var_os("HORIZON_MEM_LOG").is_some()) {
        return;
    }
    horizon_threads::trim_heap();
    let mb = std::fs::read_to_string("/proc/self/statm")
        .ok()
        .and_then(|s| s.split_whitespace().nth(1)?.parse::<u64>().ok())
        .map_or(0, |pages| (pages * 4096) >> 20);
    log::info!("memory after {stage}: {mb} MB");
}
