//! Noises a mod seeds under another identifier than their own.

/// The identifier a noise's random is hashed from when a mod seeds it under another than its
/// own: the non-vanilla key `"horizon_seed"`, written by an exporter that knows how the mod
/// seeds it (Tectonic seeds `tectonic:parameter/<x>` as `minecraft:<x>`). None: its own.
pub fn noise_seed(json: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(json).ok()?;
    value.get("horizon_seed")?.as_str().map(str::to_owned)
}

/// The identifier the noise `name` (its JSON `json`) is hashed from: [`noise_seed`], else
/// `name`.
pub(super) fn id(json: &str, name: &str) -> String {
    noise_seed(json).unwrap_or_else(|| name.to_owned())
}
