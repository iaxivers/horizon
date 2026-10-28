//! Fabric Loader and its libraries (`fabric-libs.tsv`) and Fabric API (`fabric-api.tsv`),
//! pinned by SHA-256, downloaded into a folder once and checked on every use. Shared by
//! `build.rs` (the bridge's Java compiles against the loader) and the game (the loader goes on
//! the JVM's class path, Fabric API among the mods).

use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::{Path, PathBuf};

const LIBS: &str = include_str!("../fabric-libs.tsv");
const FABRIC_API: &str = include_str!("../fabric-api.tsv");

/// One pinned jar.
pub struct Lib {
    pub file: &'static str,
    pub url: &'static str,
    pub sha256: &'static str,
}

/// Fabric Loader and its libraries, the loader first.
pub fn list() -> Vec<Lib> {
    parse(LIBS)
}

fn parse(text: &'static str) -> Vec<Lib> {
    text.lines()
        .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
        .filter_map(|l| {
            let mut parts = l.split('\t');
            Some(Lib {
                file: parts.next()?,
                url: parts.next()?,
                sha256: parts.next()?.trim(),
            })
        })
        .collect()
}

fn sha256(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// Fabric Loader and its libraries in `dir`, downloading the missing or damaged ones.
pub fn ensure(dir: &Path) -> Result<Vec<PathBuf>, String> {
    ensure_list(dir, list())
}

/// Fabric API's jar in `dir`, downloaded when missing or damaged.
pub fn ensure_fabric_api(dir: &Path) -> Result<PathBuf, String> {
    ensure_list(dir, parse(FABRIC_API))?
        .pop()
        .ok_or_else(|| "fabric-api.tsv pins no jar".to_string())
}

fn ensure_list(dir: &Path, list: Vec<Lib>) -> Result<Vec<PathBuf>, String> {
    std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    let mut paths = Vec::new();
    for lib in list {
        let path = dir.join(lib.file);
        let good = std::fs::read(&path)
            .map(|bytes| sha256(&bytes) == lib.sha256)
            .unwrap_or(false);
        if !good {
            let bytes = download(lib.url)?;
            let got = sha256(&bytes);
            if got != lib.sha256 {
                return Err(format!(
                    "{} has SHA-256 {got}, expected {}: refusing it",
                    lib.url, lib.sha256
                ));
            }
            let partial = path.with_extension("jar.partial");
            std::fs::write(&partial, &bytes).map_err(|e| format!("{}: {e}", partial.display()))?;
            std::fs::rename(&partial, &path).map_err(|e| format!("{}: {e}", path.display()))?;
        }
        paths.push(path);
    }
    Ok(paths)
}

fn download(url: &str) -> Result<Vec<u8>, String> {
    let response = ureq::get(url)
        .call()
        .map_err(|e| format!("downloading {url}: {e}"))?;
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take(64 << 20)
        .read_to_end(&mut bytes)
        .map_err(|e| format!("downloading {url}: {e}"))?;
    Ok(bytes)
}
