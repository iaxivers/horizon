//! The Fabric mods builds the java-bridge fork publishes itself (`.github/workflows/java-mods-release.yml`),
//! for versions whose own release has no `<asset>-java`: one moving pre-release, tag [`TAG`] on
//! [`REPO`], holding the Linux and Windows builds, [`MANIFEST`] (the commit and the preparation
//! format the build reads) and SHA256SUMS signed for the [`TAG`] channel with the fork's own key
//! (`launcher/java-mods-keys.txt`), never the main release keys. A build is installed only when
//! its checksum is in that signed list and the installed version prepares the format it reads.
use crate::backend;
use horizon_release_sign::{parse_keys, PublicKey, SIGNATURE, SUMS};
use std::path::Path;

/// The repository publishing the builds (a build-time `HORIZON_JAVA_MODS_REPO` overrides it).
pub const REPO: &str = match option_env!("HORIZON_JAVA_MODS_REPO") {
    Some(repo) => repo,
    None => "iaxivers/horizon-java-bridge",
};
/// The release's tag, and the channel its checksums are signed for (`horizon release java-mods`).
pub const TAG: &str = "java-mods";
/// What the build is: `{"commit": …, "output_version": …}`, listed in the signed checksums.
pub const MANIFEST: &str = "java-mods.json";
/// What this launcher and the Fabric mods builds it installs agree on (how Play starts the
/// build: its arguments, variables and the owner gate's word, `java_variant::switch_command`).
/// The release workflow (`.github/workflows/java-mods-release.yml`) writes this same number,
/// read from this line, into [`MANIFEST`] as `contract`; a manifest without one is contract 1.
/// [`fits`] installs only a build of this contract, so an older launcher never takes a build
/// that would refuse its mods.
///
/// **Bump it** (2, 3…) in the same commit as a change to the game's side of that agreement
/// (`horizon_jvm::gate`, `game/java_mods/bridge.rs` `opted_in`) that a launcher built before
/// it can't satisfy: players with that older launcher are then told to download a new one
/// instead of getting a build that runs no mods.
pub const CONTRACT: u32 = 1;
/// The page players download the fork's launcher from.
pub fn release_page() -> String {
    format!("https://github.com/{REPO}/releases/tag/{TAG}")
}
/// The public keys the fork's builds are signed with, one base64 key per line (`#` comments).
const KEYS: &str = include_str!("../java-mods-keys.txt");

/// The keys built in from java-mods-keys.txt.
pub fn keys() -> Vec<PublicKey> {
    parse_keys(KEYS).unwrap_or_default()
}

/// Where the release's file `name` downloads from.
pub fn asset_url(name: &str) -> String {
    format!("https://github.com/{REPO}/releases/download/{TAG}/{name}")
}

/// The published build: its commit, the preparation format (`horizon_prepare::OUTPUT_VERSION`)
/// it was built with, and the launcher [`CONTRACT`] it was built for.
#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize)]
pub struct Manifest {
    pub commit: String,
    pub output_version: u32,
    /// Missing in the manifests published before it existed: those are contract 1.
    #[serde(default = "first_contract")]
    pub contract: u32,
}

fn first_contract() -> u32 {
    1
}

/// The signed SHA-256 of `asset` in the fork's release, fetching its checksums with `fetch`.
pub fn digest(
    asset: &str,
    keys: &[PublicKey],
    fetch: &impl Fn(&str) -> Result<Option<Vec<u8>>, String>,
) -> Result<String, String> {
    if keys.is_empty() {
        return Err(crate::i18n::trf(
            "This launcher can't check Fabric mods builds (it trusts no key for them): download \
             the launcher again from {link}",
            &[("link", &release_page())],
        ));
    }
    let sums = fetch(&asset_url(SUMS))?;
    let signature =
        fetch(&asset_url(SIGNATURE))?.map(|bytes| String::from_utf8_lossy(&bytes).into_owned());
    if sums.is_none() {
        return Err(format!(
            "{REPO} has published no Fabric mods build yet ({TAG})"
        ));
    }
    horizon_release_sign::signed_digest(TAG, asset, sums.as_deref(), signature.as_deref(), keys)
}

/// The release's manifest, checked against its signed checksum.
pub fn manifest(
    keys: &[PublicKey],
    fetch: &impl Fn(&str) -> Result<Option<Vec<u8>>, String>,
) -> Result<Manifest, String> {
    let sha256 = digest(MANIFEST, keys, fetch)?;
    let bytes = fetch(&asset_url(MANIFEST))?
        .ok_or_else(|| format!("{REPO}'s {TAG} release has no {MANIFEST}"))?;
    horizon_release_sign::check(MANIFEST, &sha256, &horizon_release_sign::sha256_hex(&bytes))?;
    serde_json::from_slice(&bytes).map_err(|e| format!("{MANIFEST}: {e}"))
}

/// Whether the build fits this launcher ([`CONTRACT`]) and a version that prepares format
/// `prepared` (None: unknown).
pub fn fits(manifest: &Manifest, prepared: Option<u32>, tag: &str) -> Result<(), String> {
    fits_launcher(manifest.contract, CONTRACT)?;
    match prepared {
        Some(v) if v == manifest.output_version => Ok(()),
        Some(v) => {
            Err(format!(
            "{REPO}'s Fabric mods build (commit {}) reads prepared files of format {}, and {tag} \
             prepares format {v}: install a {} version",
            short(&manifest.commit),
            manifest.output_version,
            if v < manifest.output_version { "newer" } else { "older" },
        ))
        }
        None => Err(format!("{tag}'s preparation format is unknown")),
    }
}

/// Whether a build of contract `build` plays from a launcher of contract `launcher`.
fn fits_launcher(build: u32, launcher: u32) -> Result<(), String> {
    if build > launcher {
        Err(crate::i18n::trf(
            "This Fabric mods build needs a newer launcher: download it from {link}",
            &[("link", &release_page())],
        ))
    } else if build < launcher {
        Err(crate::i18n::tr(
            "This launcher needs a newer Fabric mods build than the one published: wait for the \
             next build, or use the launcher you had before.",
        )
        .to_string())
    } else {
        Ok(())
    }
}

/// What the install marker says for a build from this release.
pub fn marker(manifest: &Manifest) -> String {
    format!("{REPO}@{}\n", manifest.commit)
}

/// The commit an install marker names, when it is from this release.
pub fn marked_commit(text: &str) -> Option<&str> {
    text.trim().strip_prefix(REPO)?.strip_prefix('@')
}

fn short(commit: &str) -> &str {
    commit.get(..9).unwrap_or(commit)
}

/// The installed version folder's preparation format.
pub fn prepared_format(version: &Path) -> Option<u32> {
    backend::output_version_at(version)
}

#[cfg(test)]
mod tests {
    use super::*;
    use horizon_release_sign::{sha256_hex, sums_file, SigningKey};
    use std::collections::HashMap;

    fn release(
        key: &SigningKey,
        channel: &str,
        files: &[(&str, &[u8])],
    ) -> HashMap<String, Vec<u8>> {
        let sums = sums_file(
            &files
                .iter()
                .map(|(n, b)| (n.to_string(), sha256_hex(b)))
                .collect::<Vec<_>>(),
        );
        let mut served: HashMap<String, Vec<u8>> = files
            .iter()
            .map(|(n, b)| (asset_url(n), b.to_vec()))
            .collect();
        served.insert(
            asset_url(SIGNATURE),
            key.sign(channel, sums.as_bytes()).into_bytes(),
        );
        served.insert(asset_url(SUMS), sums.into_bytes());
        served
    }

    fn fetcher(
        served: HashMap<String, Vec<u8>>,
    ) -> impl Fn(&str) -> Result<Option<Vec<u8>>, String> {
        move |url| Ok(served.get(url).cloned())
    }

    const BUILD: &[u8] = b"the java build";
    const JSON: &[u8] = br#"{"commit":"0123456789abcdef","output_version":3,"date":"2026-11-05"}"#;

    #[test]
    fn a_build_signed_for_the_channel_by_a_listed_key_installs() {
        let (key, _) = SigningKey::generate().unwrap();
        let fetch = fetcher(release(
            &key,
            TAG,
            &[("game-java.zip", BUILD), (MANIFEST, JSON)],
        ));
        let keys = [key.public()];
        assert_eq!(
            digest("game-java.zip", &keys, &fetch),
            Ok(sha256_hex(BUILD))
        );
        let manifest = manifest(&keys, &fetch).unwrap();
        assert_eq!(manifest.output_version, 3);
        assert_eq!(marked_commit(&marker(&manifest)), Some("0123456789abcdef"));
    }

    #[test]
    fn other_keys_other_channels_and_tampered_manifests_are_refused() {
        let (key, _) = SigningKey::generate().unwrap();
        let (other, _) = SigningKey::generate().unwrap();
        let files: &[(&str, &[u8])] = &[("game-java.zip", BUILD), (MANIFEST, JSON)];
        // The main repository's key, or the fork's key over another channel's message.
        let fetch = fetcher(release(&other, TAG, files));
        assert!(digest("game-java.zip", &[key.public()], &fetch).is_err());
        let fetch = fetcher(release(&key, "nightly", files));
        assert!(digest("game-java.zip", &[key.public()], &fetch).is_err());
        // No key built in: nothing is fetched or trusted.
        assert!(digest("game-java.zip", &[], &|_: &str| panic!("fetched")).is_err());
        // A manifest that isn't the signed one.
        let mut served = release(&key, TAG, files);
        served.insert(
            asset_url(MANIFEST),
            br#"{"commit":"x","output_version":9}"#.to_vec(),
        );
        assert!(manifest(&[key.public()], &fetcher(served)).is_err());
        // Nothing published yet.
        let none = |_: &str| -> Result<Option<Vec<u8>>, String> { Ok(None) };
        assert!(digest("game-java.zip", &[key.public()], &none)
            .unwrap_err()
            .contains("no Fabric mods build yet"));
    }

    #[test]
    fn the_build_fits_only_versions_preparing_its_format() {
        let m = Manifest {
            commit: "0123456789abcdef".into(),
            output_version: 3,
            contract: CONTRACT,
        };
        assert!(fits(&m, Some(3), "nightly-2026-11-05").is_ok());
        assert!(fits(&m, Some(2), "v").unwrap_err().contains("newer"));
        assert!(fits(&m, Some(4), "v").unwrap_err().contains("older"));
        assert!(fits(&m, None, "v").is_err());
        assert_eq!(marked_commit("someone/else@abc"), None);
    }

    /// A manifest from before the field is contract 1; a build for another launcher contract
    /// is refused, a newer one with the release page to download the launcher from.
    #[test]
    fn a_build_fits_only_the_launcher_contract_it_was_made_for() {
        let old: Manifest = serde_json::from_slice(JSON).unwrap();
        assert_eq!(old.contract, 1);
        let two: Manifest = serde_json::from_str(
            r#"{"commit":"0123456789abcdef","output_version":3,"contract":2}"#,
        )
        .unwrap();
        assert_eq!(two.contract, 2);
        assert!(fits_launcher(1, 1).is_ok());
        let newer = fits_launcher(2, 1).unwrap_err();
        assert!(newer.contains("newer launcher"), "{newer}");
        assert!(newer.contains(&release_page()), "{newer}");
        assert!(fits_launcher(1, 2)
            .unwrap_err()
            .contains("newer Fabric mods build"));
        // `fits` asks the contract before the preparation format.
        let ahead = Manifest {
            contract: CONTRACT + 1,
            ..two
        };
        assert!(fits(&ahead, Some(3), "v")
            .unwrap_err()
            .contains("newer launcher"));
    }
}
