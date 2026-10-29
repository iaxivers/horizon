//! The NeoForge oracle's facts (`tools/neoforge_oracle.py export`, in
//! `<work>/oracle/facts.json`): every block, item, recipe and tag the pack's
//! mods register, read from the running game, and the vanilla blocks beside
//! them. Carry-over uses them in place of guesses: a block's template is the
//! vanilla block of the same class and states whose shapes and sounds match
//! best, and the declared properties are the mod's own numbers.
use serde::Deserialize;
use serde_json::{Map, Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::Path,
};

#[derive(Debug, Deserialize)]
pub struct Facts {
    pub blocks: BTreeMap<String, Block>,
    pub items: BTreeMap<String, Item>,
    #[serde(default)]
    pub tags: Tags,
    /// Every vanilla state's shapes, for [`Facts::own_states`] (made once).
    #[serde(skip)]
    shapes: std::sync::OnceLock<ShapeIndex>,
}

/// Vanilla states by their (outline, collision), and every one with its shapes.
#[derive(Debug, Default)]
struct ShapeIndex {
    exact: BTreeMap<(String, String), String>,
    /// Each state, its outline's bounds and its collision's.
    all: Vec<(String, Option<[f64; 6]>, Option<[f64; 6]>)>,
}

#[derive(Debug, Default, Deserialize)]
pub struct Tags {
    #[serde(default)]
    pub block: BTreeMap<String, Vec<String>>,
    #[serde(default)]
    pub item: BTreeMap<String, Vec<String>>,
}

#[derive(Debug, Deserialize)]
pub struct Block {
    /// The class and its superclasses, most derived first.
    pub class: Vec<String>,
    pub properties: BTreeMap<String, Vec<String>>,
    pub default: String,
    pub item: String,
    pub explosion_resistance: f32,
    pub friction: f32,
    pub speed_factor: f32,
    pub jump_factor: f32,
    #[serde(default)]
    pub block_set_type: Option<SetType>,
    #[serde(default)]
    pub wood_type: Option<String>,
    pub states: BTreeMap<String, State>,
}

#[derive(Debug, Deserialize)]
pub struct SetType {
    pub name: String,
    pub can_open_by_hand: bool,
}

#[derive(Debug, Deserialize)]
pub struct State {
    pub hardness: f32,
    pub emission: u8,
    pub requires_tool: bool,
    pub map_color_id: u8,
    pub sound: Value,
    pub outline: Value,
    pub collision: Value,
    /// What breaking the state drops, per tool (`hand`, `diamond_pickaxe`,
    /// `shears`, `silk_touch_pickaxe`...): item id → count.
    #[serde(default)]
    pub drops: BTreeMap<String, BTreeMap<String, u32>>,
}

#[derive(Debug, Deserialize)]
pub struct Item {
    pub class: String,
    pub max_stack_size: u8,
    pub rarity: String,
    #[serde(default)]
    pub block: Option<String>,
    #[serde(default)]
    pub food: Option<Value>,
    /// Its default components in their JSON form (exporters that write them).
    #[serde(default)]
    pub components: Map<String, Value>,
}

/// The components carried onto an item's template: what it does when used, worn or held.
/// (Its name, model and tooltip come with the content mod's own id and assets.)
const CARRIED_COMPONENTS: &[&str] = &[
    "minecraft:food",
    "minecraft:consumable",
    "minecraft:use_remainder",
    "minecraft:use_cooldown",
    "minecraft:max_damage",
    "minecraft:tool",
    "minecraft:weapon",
    "minecraft:attribute_modifiers",
    "minecraft:equippable",
    "minecraft:enchantable",
    "minecraft:repairable",
    "minecraft:damage_resistant",
    "minecraft:death_protection",
    "minecraft:blocks_attacks",
    "minecraft:glider",
    "minecraft:fireworks",
    "minecraft:jukebox_playable",
    "minecraft:instrument",
];

/// Whether a JSON value names only ids Horizon has: vanilla's (and tags of them), or the
/// mod's own items (`own`), not its effects, sounds or other registries' entries.
fn known_ids(v: &Value, own: &str) -> bool {
    match v {
        Value::String(s) => {
            let id = s.strip_prefix('#').unwrap_or(s);
            match id.split_once(':') {
                Some((ns, path))
                    if !ns.is_empty()
                        && ns
                            .chars()
                            .all(|c| matches!(c, 'a'..='z' | '0'..='9' | '_' | '-' | '.'))
                        && !path.is_empty()
                        && !path.contains(' ') =>
                {
                    ns == "minecraft" || ns == own
                }
                _ => true,
            }
        }
        Value::Array(a) => a.iter().all(|v| known_ids(v, own)),
        Value::Object(o) => o.values().all(|v| known_ids(v, own)),
        _ => true,
    }
}

const VANILLA_PACKAGE: &str = "net.minecraft.";

/// A shape's bounds (a box list: `[x0 y0 z0 x1 y1 z1]...`); `None` when empty.
fn bounds(v: &Value) -> Option<[f64; 6]> {
    let boxes = v.as_array()?;
    let mut out = [f64::MAX, f64::MAX, f64::MAX, f64::MIN, f64::MIN, f64::MIN];
    for b in boxes {
        let c: Vec<f64> = b.as_array()?.iter().filter_map(Value::as_f64).collect();
        if c.len() != 6 {
            return None;
        }
        for i in 0..3 {
            out[i] = out[i].min(c[i]);
            out[i + 3] = out[i + 3].max(c[i + 3]);
        }
    }
    (!boxes.is_empty()).then_some(out)
}

/// How far apart two shapes' bounds are (a box list each: `[x0 y0 z0 x1 y1 z1]...`);
/// an empty shape is far from any other.
fn bounds_distance(a: Option<[f64; 6]>, b: Option<[f64; 6]>) -> f64 {
    match (a, b) {
        (Some(x), Some(y)) => x.iter().zip(y).map(|(p, q)| (p - q).abs()).sum(),
        (None, None) => 0.0,
        _ => 6.0,
    }
}

/// A state key with its properties sorted, so keys compare across blocks.
/// Which states of a block drop the block itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SelfDrop {
    /// Every state: `dropSelf`.
    Always,
    /// Only the states where this property has this value.
    When(String, String),
}

/// A state key's `property=value` pairs.
fn pairs(key: &str) -> BTreeSet<(String, String)> {
    key.split(',')
        .filter_map(|p| p.split_once('='))
        .map(|(k, v)| (k.to_owned(), v.to_owned()))
        .collect()
}

pub fn state_key(key: &str) -> String {
    let mut parts: Vec<&str> = key.split(',').filter(|p| !p.is_empty()).collect();
    parts.sort_unstable();
    parts.join(",")
}

impl Block {
    /// The first vanilla class in the chain.
    pub fn vanilla_class(&self) -> &str {
        self.class
            .iter()
            .find(|c| c.starts_with(VANILLA_PACKAGE))
            .map_or("", String::as_str)
    }
    /// Its own class is a mod class: behaviour code the template may not have.
    pub fn custom_class(&self) -> bool {
        self.class
            .first()
            .is_some_and(|c| !c.starts_with(VANILLA_PACKAGE))
    }
    /// Every state drops one `item` (the block's own) with every tool, except
    /// where the state needs a tool and gets none: vanilla's `dropSelf`. Or
    /// only the states where one property has one value do, and the others
    /// drop nothing with any tool: a door's lower half (`createDoorTable`).
    /// Mods often register these tables in code, so their jar has no file.
    pub fn drops_itself(&self, item: &str) -> Option<SelfDrop> {
        let one = BTreeMap::from([(item.to_owned(), 1)]);
        let (mut dropping, mut nothing) = (Vec::new(), Vec::new());
        for (key, state) in &self.states {
            if state.drops.is_empty() {
                continue;
            }
            let mut drops = false;
            let mut short = false;
            for got in state.drops.values() {
                if *got == one {
                    drops = true;
                } else if got.is_empty() {
                    short = true;
                } else {
                    return None;
                }
            }
            match (drops, short) {
                (true, true) if !state.requires_tool => return None,
                (true, _) => dropping.push(pairs(key)),
                (false, _) => nothing.push(pairs(key)),
            }
        }
        let (first, rest) = dropping.split_first()?;
        if nothing.is_empty() {
            return Some(SelfDrop::Always);
        }
        first
            .iter()
            .filter(|pair| rest.iter().all(|s| s.contains(*pair)))
            .find(|pair| nothing.iter().all(|s| !s.contains(*pair)))
            .map(|(p, v)| SelfDrop::When(p.clone(), v.clone()))
    }
    fn default_state(&self) -> Option<&State> {
        self.states
            .get(&self.default)
            .or_else(|| self.states.values().next())
    }
    fn same_states(&self, other: &Block) -> bool {
        self.properties.len() == other.properties.len()
            && self.properties.iter().all(|(k, v)| {
                other.properties.get(k).is_some_and(|w| {
                    v.iter().collect::<BTreeSet<_>>() == w.iter().collect::<BTreeSet<_>>()
                })
            })
    }
    fn by_key(&self) -> BTreeMap<String, &State> {
        self.states.iter().map(|(k, s)| (state_key(k), s)).collect()
    }
}

/// The template carry-over gives a mod block.
#[derive(Debug, Clone, PartialEq)]
pub struct Choice {
    pub template: String,
    /// `data/<mod>/horizon/block/<name>.json`'s `properties`.
    pub properties: Map<String, Value>,
    /// What the template does not reproduce, for the report.
    pub differences: Vec<String>,
}

impl Facts {
    pub fn read(path: &Path) -> Result<Facts, String> {
        let bytes = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
        serde_json::from_slice(&bytes).map_err(|e| format!("{}: {e}", path.display()))
    }

    /// The vanilla block that best stands in for `id`: the same vanilla
    /// class and states, then the most states with the same outline and
    /// collision, then the same sounds, set type, wood type and hardness.
    /// `None` when no vanilla block has its class and states.
    pub fn template(&self, id: &str, allowed: &dyn Fn(&str) -> bool) -> Option<Choice> {
        let block = self.blocks.get(id)?;
        let class = block.vanilla_class();
        let own = block.by_key();
        let ours = block.default_state()?;
        let mut best: Option<(i64, &str, &Block)> = None;
        for (name, v) in &self.blocks {
            if !name.starts_with("minecraft:")
                || v.vanilla_class() != class
                || v.custom_class()
                || !block.same_states(v)
                || !allowed(name)
            {
                continue;
            }
            let theirs = v.by_key();
            let shapes = own
                .iter()
                .filter(|(k, s)| {
                    theirs
                        .get(*k)
                        .is_some_and(|t| t.outline == s.outline && t.collision == s.collision)
                })
                .count() as i64;
            let Some(td) = v.default_state() else {
                continue;
            };
            let same_set = match (&block.block_set_type, &v.block_set_type) {
                (Some(a), Some(b)) => {
                    (a.name == b.name) as i64 * 2
                        + (a.can_open_by_hand == b.can_open_by_hand) as i64 * 8
                }
                _ => 0,
            };
            let score = shapes * 1000
                + (td.sound == ours.sound) as i64 * 100
                + same_set * 10
                + (block.wood_type.is_some() && block.wood_type == v.wood_type) as i64 * 20
                + (td.hardness == ours.hardness) as i64 * 5
                + (td.map_color_id == ours.map_color_id) as i64;
            if best.is_none_or(|(s, n, _)| score > s || (score == s && name.as_str() < n)) {
                best = Some((score, name, v));
            }
        }
        let (_, template, t) = best?;
        Some(self.choice(block, template, t, allowed))
    }

    /// For a block no vanilla block stands in for (its properties are its own): a template
    /// without properties or behaviour (stone), and its own states, each with the vanilla
    /// state whose collision and outline it has (else the nearest), for the declaration's
    /// `states`.
    pub fn own_states(&self, id: &str, allowed: &dyn Fn(&str) -> bool) -> Option<(Choice, Value)> {
        let block = self.blocks.get(id)?;
        let template = [
            "minecraft:stone",
            "minecraft:smooth_stone",
            "minecraft:dirt",
        ]
        .into_iter()
        .find(|t| allowed(t) && self.blocks.get(*t).is_some_and(|b| b.properties.is_empty()))?;
        let mut choice = self.choice(block, template, &self.blocks[template], allowed);
        let ShapeIndex { exact, all } = self.shapes.get_or_init(|| {
            let mut index = ShapeIndex::default();
            for (name, v) in &self.blocks {
                if !name.starts_with("minecraft:") || !allowed(name) {
                    continue;
                }
                for (key, st) in &v.states {
                    let full = format!("{name}[{}]", state_key(key));
                    index
                        .exact
                        .entry((st.outline.to_string(), st.collision.to_string()))
                        .or_insert_with(|| full.clone());
                    index
                        .all
                        .push((full, bounds(&st.outline), bounds(&st.collision)));
                }
            }
            index
        });
        let default = state_key(&block.default);
        let default_light = block.default_state().map_or(0, |s| s.emission);
        let mut near = 0;
        let mut states = Vec::new();
        for (key, st) in &block.states {
            let key = state_key(key);
            let shape = match exact.get(&(st.outline.to_string(), st.collision.to_string())) {
                Some(s) => s.clone(),
                None => {
                    near += 1;
                    let (outline, collision) = (bounds(&st.outline), bounds(&st.collision));
                    let d = |(_, o, c): &(String, Option<[f64; 6]>, Option<[f64; 6]>)| {
                        bounds_distance(*c, collision) + bounds_distance(*o, outline) * 0.5
                    };
                    all.iter()
                        .min_by(|a, b| d(a).total_cmp(&d(b)).then(a.0.cmp(&b.0)))
                        .map(|(n, _, _)| n.clone())?
                }
            };
            let properties: Map<String, Value> = pairs(&key)
                .into_iter()
                .map(|(k, v)| (k, Value::String(v)))
                .collect();
            let mut v = json!({"properties": properties, "shape": shape});
            if st.emission != default_light {
                v["light"] = json!(st.emission);
            }
            if key == default {
                v["default"] = json!(true);
            }
            states.push(v);
        }
        if near > 0 {
            choice.differences.push(format!(
                "shapes: {near} of {} states take the nearest vanilla state's",
                states.len()
            ));
        }
        choice.differences.push(format!(
            "its own states: behaviour beyond a plain block ({})",
            block.class.first().map_or("", String::as_str)
        ));
        Some((choice, Value::Array(states)))
    }

    fn choice(
        &self,
        block: &Block,
        template: &str,
        t: &Block,
        allowed: &dyn Fn(&str) -> bool,
    ) -> Choice {
        let (ours, theirs) = (block.default_state(), t.default_state());
        let mut properties = Map::new();
        let mut differences = Vec::new();
        if let Some(s) = ours {
            properties.insert("hardness".into(), json!(s.hardness));
            properties.insert(
                "explosion_resistance".into(),
                json!(block.explosion_resistance),
            );
            properties.insert("light".into(), json!(s.emission));
            properties.insert("requires_tool".into(), json!(s.requires_tool));
            properties.insert("friction".into(), json!(block.friction));
            properties.insert("map_color".into(), json!(s.map_color_id));
            if theirs.is_none_or(|t| t.sound != s.sound) {
                match self.sound_block(&s.sound, allowed) {
                    Some(b) => {
                        properties.insert("sounds".into(), json!(b));
                    }
                    None => differences.push("sounds: no vanilla block has them".into()),
                }
            }
        }
        if (block.speed_factor, block.jump_factor) != (t.speed_factor, t.jump_factor) {
            differences.push(format!(
                "speed and jump factors {} {} (template {} {})",
                block.speed_factor, block.jump_factor, t.speed_factor, t.jump_factor
            ));
        }
        let theirs = t.by_key();
        let mut shapes = 0;
        let mut varying = BTreeSet::new();
        let first = ours.map(|s| (s.hardness, s.emission, s.requires_tool, s.map_color_id));
        for (k, s) in block.by_key() {
            let Some(v) = theirs.get(&k) else {
                continue;
            };
            if v.outline != s.outline || v.collision != s.collision {
                shapes += 1;
            }
            if Some((s.hardness, s.emission, s.requires_tool, s.map_color_id)) != first {
                varying.insert("per-state hardness, light or map colour");
            }
        }
        if shapes > 0 {
            differences.push(format!(
                "shapes differ from {template} in {shapes} of {} states",
                block.states.len()
            ));
        }
        differences.extend(varying.into_iter().map(str::to_owned));
        if block.custom_class() {
            differences.push(format!(
                "class {} (behaviour beyond {})",
                block.class[0],
                block.vanilla_class()
            ));
        }
        Choice {
            template: template.to_owned(),
            properties,
            differences,
        }
    }

    /// A vanilla block whose default state has these sounds.
    fn sound_block(&self, sound: &Value, allowed: &dyn Fn(&str) -> bool) -> Option<&str> {
        self.blocks
            .iter()
            .filter(|(n, _)| n.starts_with("minecraft:") && allowed(n))
            .find(|(_, b)| b.default_state().is_some_and(|s| &s.sound == sound))
            .map(|(n, _)| n.as_str())
    }

    /// An item declaration for a mod item that is not a declared block's:
    /// a vanilla template item with its stack size and rarity, and what it
    /// does not reproduce.
    pub fn item(&self, id: &str) -> Result<(Value, Vec<String>), String> {
        let item = self.items.get(id).ok_or("not in the oracle")?;
        if item.block.is_some() {
            return Err("a block item without a block".into());
        }
        let mut differences = Vec::new();
        if !item.class.starts_with(VANILLA_PACKAGE) {
            differences.push(format!("class {} (behaviour beyond Item)", item.class));
        }
        let food = item.food.is_some() || item.components.contains_key("minecraft:food");
        let template = if food {
            "minecraft:bread"
        } else {
            "minecraft:stick"
        };
        let mut decl = json!({"template": template});
        let own = id.split_once(':').map_or("", |(ns, _)| ns);
        let mut components = Map::new();
        for (name, value) in &item.components {
            if !CARRIED_COMPONENTS.contains(&name.as_str()) {
                continue;
            }
            let mut value = value.clone();
            // Effects of the mod's own (Farmer's Delight's Comfort...) are left out, the rest kept.
            if let Some(Value::Array(effects)) = value.get_mut("on_consume_effects") {
                let before = effects.len();
                effects.retain(|e| known_ids(e, ""));
                if effects.len() < before {
                    differences.push(format!(
                        "{name}: {} effect(s) of the mod's own",
                        before - effects.len()
                    ));
                }
            }
            if known_ids(&value, own) {
                components.insert(name.clone(), value);
            } else {
                differences.push(format!("{name}: names what Horizon does not have"));
            }
        }
        if food && !components.contains_key("minecraft:food") {
            return Err("food without its components (an older exporter)".into());
        }
        if !components.is_empty() {
            decl["components"] = Value::Object(components);
        }
        if item.max_stack_size != 64 {
            decl["max_stack_size"] = json!(item.max_stack_size);
        }
        if item.rarity != "common" {
            decl["rarity"] = json!(item.rarity);
        }
        Ok((decl, differences))
    }

    /// A tag's members the content mod may name: vanilla's and its own.
    pub fn tag_members(&self, kind: &str, tag: &str, own: &str) -> Option<Vec<String>> {
        let map = if kind == "block" {
            &self.tags.block
        } else {
            &self.tags.item
        };
        let members = map.get(tag)?;
        Some(
            members
                .iter()
                .filter(|m| {
                    m.split_once(':')
                        .is_some_and(|(ns, _)| ns == "minecraft" || ns == own)
                })
                .cloned()
                .collect(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state(hardness: f32, sound: &str, outline: Value) -> Value {
        json!({"hardness": hardness, "emission": 0, "requires_tool": false, "map_color_id": 13,
            "sound": {"break": sound}, "outline": outline, "collision": outline})
    }
    fn block(class: &[&str], props: Value, states: Value) -> Value {
        let default = states
            .as_object()
            .unwrap()
            .keys()
            .next()
            .unwrap()
            .to_owned();
        json!({"class": class, "properties": props, "default": default, "item": "x",
            "explosion_resistance": 3.0, "friction": 0.6, "speed_factor": 1.0,
            "jump_factor": 1.0, "states": states})
    }

    #[test]
    fn templates_follow_class_shapes_and_sounds() {
        let cube = json!([[0, 0, 0, 1, 1, 1]]);
        let low = json!([[0, 0, 0, 1, 0.9375, 1]]);
        let block_class = ["net.minecraft.world.level.block.Block"];
        let path = ["net.minecraft.world.level.block.DirtPathBlock"];
        let facts: Facts = serde_json::from_value(json!({
            "blocks": {
                "minecraft:stone": block(&block_class, json!({}), json!({"": state(1.5, "stone", cube.clone())})),
                "minecraft:oak_planks": block(&block_class, json!({}), json!({"": state(2.0, "wood", cube.clone())})),
                "minecraft:dirt_path": block(&path, json!({}), json!({"": state(0.65, "grass", low.clone())})),
                "m:wood_tile": block(&["m.Tile", block_class[0]], json!({}), json!({"": state(2.5, "wood", cube.clone())})),
                "m:path": block(&["m.Path", block_class[0]], json!({}), json!({"": state(1.0, "stone", low.clone())})),
                "m:gadget": block(&["m.Gadget", block_class[0]], json!({"mode": ["a", "b"]}),
                    json!({"mode=a": state(1.0, "stone", cube.clone()), "mode=b": state(1.0, "stone", cube)})),
            },
            "items": {}
        }))
        .unwrap();
        let any = |_: &str| true;
        let tile = facts.template("m:wood_tile", &any).unwrap();
        assert_eq!(tile.template, "minecraft:oak_planks");
        assert_eq!(tile.properties["hardness"], json!(2.5));
        assert!(!tile.properties.contains_key("sounds"));
        assert_eq!(
            tile.differences,
            ["class m.Tile (behaviour beyond net.minecraft.world.level.block.Block)"]
        );
        // A plain Block is never templated on a DirtPathBlock: shapes differ, and say so.
        let path = facts.template("m:path", &any).unwrap();
        assert_eq!(path.template, "minecraft:stone");
        assert!(
            path.differences[0].starts_with("shapes differ"),
            "{:?}",
            path.differences
        );
        assert_eq!(facts.template("m:gadget", &any), None);
        let tile = facts
            .template("m:wood_tile", &|n: &str| n != "minecraft:oak_planks")
            .unwrap();
        assert_eq!(tile.template, "minecraft:stone");
        assert_eq!(tile.differences[0], "sounds: no vanilla block has them");
    }

    #[test]
    fn drops_itself_is_vanillas_drop_self() {
        let cube = json!([[0, 0, 0, 1, 1, 1]]);
        let with = |requires_tool: bool, drops: Value| {
            let mut s = state(1.5, "stone", cube.clone());
            s["requires_tool"] = json!(requires_tool);
            s["drops"] = drops;
            let b: Block = serde_json::from_value(block(&[], json!({}), json!({"": s}))).unwrap();
            b.drops_itself("m:tile")
        };
        let me = json!({"m:tile": 1});
        let always = Some(SelfDrop::Always);
        assert_eq!(with(false, json!({"hand": me, "shears": me})), always);
        // A state that needs a tool drops nothing by hand, as dropSelf does.
        assert_eq!(
            with(true, json!({"hand": {}, "diamond_pickaxe": me})),
            always
        );
        assert_eq!(
            with(false, json!({"hand": {}, "diamond_pickaxe": me})),
            None
        );
        // Two of itself (a double slab), or something else, is not dropSelf.
        assert_eq!(with(false, json!({"hand": {"m:tile": 2}})), None);
        assert_eq!(with(false, json!({"hand": {"m:other": 1}})), None);
        assert_eq!(with(false, json!({})), None);
    }

    #[test]
    fn a_door_drops_from_its_lower_half() {
        let cube = json!([[0, 0, 0, 1, 1, 1]]);
        let me = json!({"hand": {"m:door": 1}, "shears": {"m:door": 1}});
        let none = json!({"hand": {}, "shears": {}});
        let door = |upper: &Value| {
            let mut states = serde_json::Map::new();
            for (key, drops) in [
                ("half=lower,open=false", &me),
                ("half=lower,open=true", &me),
                ("half=upper,open=false", upper),
                ("half=upper,open=true", upper),
            ] {
                let mut s = state(3.0, "wood", cube.clone());
                s["drops"] = drops.clone();
                states.insert(key.to_owned(), s);
            }
            let props = json!({"half": ["upper", "lower"], "open": ["true", "false"]});
            let b: Block =
                serde_json::from_value(block(&[], props, Value::Object(states))).unwrap();
            b.drops_itself("m:door")
        };
        assert_eq!(
            door(&none),
            Some(SelfDrop::When("half".to_owned(), "lower".to_owned()))
        );
        assert_eq!(door(&me), Some(SelfDrop::Always));
        // Something else from the upper half: not a door table.
        assert_eq!(door(&json!({"hand": {"m:other": 1}})), None);
    }

    #[test]
    fn state_keys_sort() {
        assert_eq!(
            state_key("half=lower,facing=north"),
            "facing=north,half=lower"
        );
        assert_eq!(state_key(""), "");
    }
}
