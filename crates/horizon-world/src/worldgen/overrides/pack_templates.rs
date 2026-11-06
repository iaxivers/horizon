//! Data packs' structure templates (`data/<ns>/structure/<path>.nbt`), read when a
//! structure places them.
use super::*;
use std::sync::Arc;

/// A pack's file reader for its structure templates: the bytes of a file
/// (`data/<ns>/structure/<path>.nbt`, relative to the pack root).
pub type PackReader = Arc<dyn Fn(&str) -> Option<Vec<u8>> + Send + Sync>;

/// The packs' readers by pack id. Not part of what the overrides compare or
/// hash: the template entries (pack id and file) already say what is read.
#[derive(Clone, Default)]
struct Readers(BTreeMap<String, PackReader>);
impl PartialEq for Readers {
    fn eq(&self, _: &Self) -> bool {
        true
    }
}
impl Eq for Readers {}
impl std::hash::Hash for Readers {
    fn hash<H: std::hash::Hasher>(&self, _: &mut H) {}
}
impl std::fmt::Debug for Readers {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_list().entries(self.0.keys()).finish()
    }
}

/// `ns:path` for a template file `data/<ns>/structure/<path>.nbt`
/// (StructureTemplateManager's `FileToIdConverter("structure", ".nbt")`).
fn template_id(file: &str) -> Option<String> {
    let (ns, rest) = file.strip_prefix("data/")?.split_once('/')?;
    let path = rest.strip_prefix("structure/")?.strip_suffix(".nbt")?;
    (!path.is_empty()).then(|| format!("{ns}:{path}"))
}

/// Data packs' structure templates (`data/<ns>/structure/<path>.nbt`): which
/// pack's file each id is, read when a structure places it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
pub struct PackTemplates {
    /// Template id → (pack id, file) of the highest pack that has it.
    files: BTreeMap<String, (String, String)>,
    /// How each pack's files are read.
    readers: Readers,
}
impl PackTemplates {
    /// The pack file `file` of the pack `pack`, if it is a structure template: read
    /// when a structure places it, through the pack's reader.
    pub(super) fn add(&mut self, pack: &str, file: &str) -> bool {
        let Some(id) = template_id(file) else {
            return false;
        };
        self.files.insert(id, (pack.to_owned(), file.to_owned()));
        true
    }
    pub fn is_empty(&self) -> bool {
        self.files.is_empty()
    }
    /// Whether a pack gives the template `id` (`ns:path`).
    pub fn contains(&self, id: &str) -> bool {
        self.files.contains_key(id)
    }
    /// The highest pack's file for the template `id` (`ns:path`), gzip NBT
    /// as in the pack: vanilla's ResourceManagerTemplateSource reads the
    /// packs' resource stack, the top one winning, over the game's own.
    /// None when no pack has it, or its pack has no reader or can't read it.
    pub fn read(&self, id: &str) -> Option<Vec<u8>> {
        let (pack, file) = self.files.get(id)?;
        (self.readers.0.get(pack)?)(file)
    }
}
impl WorldgenOverrides {
    /// How the template files of the pack `pack` are read.
    pub fn set_reader(&mut self, pack: &str, reader: PackReader) {
        self.templates.readers.0.insert(pack.to_owned(), reader);
    }
    /// The packs' structure templates.
    pub fn templates(&self) -> &PackTemplates {
        &self.templates
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_highest_packs_template_is_read() {
        let mut o = WorldgenOverrides::default();
        let file = "data/minecraft/structure/ancient_city/city_center/city_center_1.nbt";
        let none = || -> Option<String> { unreachable!("templates are read when placed") };
        assert_eq!(o.add_file("low", file, none), PackFile::Read);
        assert_eq!(o.add_file("high", file, none), PackFile::Read);
        assert_eq!(
            o.add_file("high", "data/minecraft/structure/.nbt", none),
            PackFile::Other
        );
        assert!(!o.is_empty());
        let id = "minecraft:ancient_city/city_center/city_center_1";
        assert!(o.templates().contains(id));
        // No reader yet for the pack that has it.
        assert_eq!(o.templates().read(id), None);
        o.set_reader("low", Arc::new(|_| Some(b"low".to_vec())));
        o.set_reader("high", Arc::new(|f| Some(f.as_bytes().to_vec())));
        assert_eq!(o.templates().read(id).as_deref(), Some(file.as_bytes()));
        assert_eq!(o.templates().read("minecraft:other"), None);
    }
}
