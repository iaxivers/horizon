//! Where the Java bridge's time goes (`HORIZON_JAVA_PROFILE=1`): every call into Java timed by
//! where the game made it, summed, and logged every 200 server ticks as milliseconds a tick
//! (the Java side logs its tick's phases the same way: `HorizonProfile`). Off, a call costs one
//! check of a static.

use std::collections::HashMap;
use std::panic::Location;
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

/// Whether profiling is on (read once).
pub(super) fn on() -> bool {
    static ON: LazyLock<bool> =
        LazyLock::new(|| std::env::var_os("HORIZON_JAVA_PROFILE").is_some());
    *ON
}

/// Each call site's calls and their time since the last report.
static SITES: Mutex<Option<HashMap<String, (u64, Duration)>>> = Mutex::new(None);

/// A call into Java being timed; its time is added where it ends.
pub(super) struct Timed {
    site: Option<(String, Instant)>,
}

impl Timed {
    /// Times the caller's call (its file and line name it).
    #[track_caller]
    pub(super) fn here() -> Self {
        if !on() {
            return Self { site: None };
        }
        let at = Location::caller();
        let file = at.file().rsplit('/').next().unwrap_or(at.file());
        Self {
            site: Some((format!("{file}:{}", at.line()), Instant::now())),
        }
    }

    /// Times a call named `name` (worker threads' calls: worldgen).
    #[cfg_attr(not(feature = "java-mods"), allow(dead_code))]
    pub(super) fn named(name: &str) -> Self {
        if !on() {
            return Self { site: None };
        }
        Self {
            site: Some((name.to_owned(), Instant::now())),
        }
    }
}

impl Drop for Timed {
    fn drop(&mut self) {
        let Some((site, started)) = self.site.take() else {
            return;
        };
        let took = started.elapsed();
        let mut sites = SITES.lock().unwrap_or_else(|e| e.into_inner());
        let entry = sites
            .get_or_insert_with(HashMap::new)
            .entry(site)
            .or_default();
        entry.0 += 1;
        entry.1 += took;
    }
}

/// Once a server tick: every 200, the sites' time as ms a tick, the slowest first.
pub(super) fn tick() {
    static TICKS: Mutex<u32> = Mutex::new(0);
    if !on() {
        return;
    }
    let mut ticks = TICKS.lock().unwrap_or_else(|e| e.into_inner());
    *ticks += 1;
    if *ticks < 200 {
        return;
    }
    let n = f64::from(*ticks);
    *ticks = 0;
    let Some(sites) = SITES.lock().unwrap_or_else(|e| e.into_inner()).take() else {
        return;
    };
    let mut rows: Vec<_> = sites.into_iter().collect();
    rows.sort_by_key(|row| std::cmp::Reverse(row.1.1));
    let total: f64 = rows.iter().map(|r| r.1.1.as_secs_f64()).sum::<f64>() * 1000.0 / n;
    let mut line = format!("Java profile: {total:.2} ms a tick in calls to Java;");
    for (site, (calls, took)) in rows.iter().take(8) {
        line.push_str(&format!(
            " {site} {:.2} ms ({:.1} calls)",
            took.as_secs_f64() * 1000.0 / n,
            *calls as f64 / n
        ));
    }
    log::info!("{line}");
}
