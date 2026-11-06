//! The behaviour tables' big source files read without keeping them as JSON trees: a
//! modded pack's ran to hundreds of MB (`block-behaviours.json`) and gigabytes
//! (`gameplay-facts.json`).
use super::*;

impl Behaviours {
    /// [`Self::load_with_gameplay`], `block-behaviours.json` read only when the snapshot misses
    /// (the caller need not keep its tree: a modded pack's ran to hundreds of MB).
    pub fn load_with_gameplay_from_file<G: GameplayStateSource>(
        registry: &BlockRegistry,
        resources_dir: &Path,
        gameplay: &[G],
    ) -> io::Result<Self> {
        cached(registry, resources_dir, || {
            let behaviour = read_json(&resources_dir.join("block-behaviours.json"))?;
            build_with_gameplay(registry, resources_dir, &behaviour, gameplay)
        })
    }
}

/// `gameplay-facts.json`'s fields the behaviour tables read ([`GameplayStateSource`]), streamed
/// from the file: the rest of each row (outline edges, the other sounds: most of the file) is
/// skipped as it is read. As a JSON tree the file took ten times its size (3.4 GB for 208k
/// states, a modded pack's), though the tables keep only tens of MB.
pub(super) fn read_gameplay(path: &Path) -> io::Result<GameplayFile> {
    let file = io::BufReader::with_capacity(1 << 20, std::fs::File::open(path)?);
    serde_json::from_reader(file).map_err(|e| invalid(format!("{}: {e}", path.display())))
}

#[derive(serde::Deserialize)]
pub(super) struct GameplayFile {
    pub(super) states: Vec<GameplayRow>,
}

/// One state's gameplay row, as [`GameplayStateSource for Value`] reads it (missing fields:
/// false, 0, "", a sound's 1.0).
#[derive(serde::Deserialize, Default)]
#[serde(default)]
pub(super) struct GameplayRow {
    replaceable: bool,
    random_ticks: bool,
    solid_render: bool,
    liquid: bool,
    block_entity: bool,
    ignited_by_lava: bool,
    can_occlude: bool,
    full_collision: bool,
    skylight_down: bool,
    requires_tool: bool,
    shape_occlusion: bool,
    emission: u64,
    dampening: u64,
    hardness: f64,
    push: Box<str>,
    sound: GameplaySound,
}

#[derive(serde::Deserialize, Default)]
#[serde(default)]
struct GameplaySound {
    volume: Option<f64>,
    pitch: Option<f64>,
    place: Option<SoundId>,
    #[serde(rename = "break")]
    break_sound: Option<SoundId>,
    fall: Option<SoundId>,
}

#[derive(serde::Deserialize)]
struct SoundId {
    id: Box<str>,
}

impl GameplayStateSource for GameplayRow {
    fn flag(&self, name: &str) -> bool {
        match name {
            "replaceable" => self.replaceable,
            "random_ticks" => self.random_ticks,
            "solid_render" => self.solid_render,
            "liquid" => self.liquid,
            "block_entity" => self.block_entity,
            "ignited_by_lava" => self.ignited_by_lava,
            "can_occlude" => self.can_occlude,
            "full_collision" => self.full_collision,
            "skylight_down" => self.skylight_down,
            "requires_tool" => self.requires_tool,
            "shape_occlusion" => self.shape_occlusion,
            _ => false,
        }
    }
    fn unsigned(&self, name: &str) -> u64 {
        match name {
            "emission" => self.emission,
            "dampening" => self.dampening,
            _ => 0,
        }
    }
    fn number(&self, name: &str) -> f64 {
        if name == "hardness" {
            self.hardness
        } else {
            0.0
        }
    }
    fn text(&self, name: &str) -> &str {
        if name == "push" { &self.push } else { "" }
    }
    fn sound_number(&self, name: &str) -> f64 {
        match name {
            "volume" => self.sound.volume,
            "pitch" => self.sound.pitch,
            _ => None,
        }
        .unwrap_or(1.0)
    }
    fn sound_id(&self, name: &str) -> &str {
        match name {
            "place" => self.sound.place.as_ref(),
            "break" => self.sound.break_sound.as_ref(),
            "fall" => self.sound.fall.as_ref(),
            _ => None,
        }
        .map_or("", |s| &s.id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FLAGS: [&str; 11] = [
        "replaceable",
        "random_ticks",
        "solid_render",
        "liquid",
        "block_entity",
        "ignited_by_lava",
        "can_occlude",
        "full_collision",
        "skylight_down",
        "requires_tool",
        "shape_occlusion",
    ];

    /// The streamed rows answer every field the tables read as the JSON tree did, for a full
    /// row, a sparse one (defaults) and one with fields the tables never read.
    #[test]
    fn streamed_rows_answer_as_the_json_tree() {
        let text = r#"{"states": [
            {"replaceable": true, "random_ticks": false, "solid_render": true, "liquid": false,
             "block_entity": true, "ignited_by_lava": true, "can_occlude": true,
             "full_collision": false, "skylight_down": true, "requires_tool": true,
             "shape_occlusion": false, "emission": 15, "dampening": 3, "hardness": 1.5,
             "push": "BLOCK", "sound": {"volume": 0.8, "pitch": 1.2,
             "place": {"id": "minecraft:block.stone.place"},
             "break": {"id": "minecraft:block.stone.break"},
             "fall": {"id": "minecraft:block.stone.fall"},
             "hit": {"id": "minecraft:block.stone.hit"}}},
            {},
            {"hardness": 2, "liquid": true, "outline": [[0, 0, 0, 1, 1, 1]], "push": "DESTROY",
             "sound": {"pitch": 0.5}}
        ]}"#;
        let tree: Value = serde_json::from_str(text).unwrap();
        let tree = tree["states"].as_array().unwrap();
        let rows: GameplayFile = serde_json::from_str(text).unwrap();
        assert_eq!(rows.states.len(), tree.len());
        for (row, value) in rows.states.iter().zip(tree) {
            for name in FLAGS {
                assert_eq!(row.flag(name), value.flag(name), "{name}");
            }
            for name in ["emission", "dampening"] {
                assert_eq!(row.unsigned(name), value.unsigned(name), "{name}");
            }
            assert_eq!(row.number("hardness"), value.number("hardness"));
            assert_eq!(row.text("push"), value.text("push"));
            for name in ["volume", "pitch"] {
                assert_eq!(row.sound_number(name), value.sound_number(name), "{name}");
            }
            for name in ["place", "break", "fall"] {
                assert_eq!(row.sound_id(name), value.sound_id(name), "{name}");
            }
        }
        let [full, empty, sparse] = &rows.states[..] else {
            panic!("three rows")
        };
        assert!(full.flag("ignited_by_lava") && !full.flag("liquid"));
        assert_eq!(full.unsigned("emission"), 15);
        assert_eq!(full.sound_id("break"), "minecraft:block.stone.break");
        assert!(!empty.flag("replaceable"));
        assert_eq!((empty.number("hardness"), empty.text("push")), (0.0, ""));
        assert_eq!(empty.sound_number("volume"), 1.0);
        assert_eq!(sparse.number("hardness"), 2.0);
        assert_eq!(
            (sparse.sound_number("volume"), sparse.sound_number("pitch")),
            (1.0, 0.5)
        );
    }
}
