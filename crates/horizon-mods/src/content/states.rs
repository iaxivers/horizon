//! A block's own states (`states`, or `state_table` for many): their file forms, how a
//! table reads as the states it stands for, and their checks.
use super::*;

/// A block's own states in a compact form, for blocks with many (a pipe's 76k): each
/// property's values and the distinct shapes once, then a row per state, in order:
/// `[shape, value of the 1st property, of the 2nd, ...]` as indices. `light` maps a row to
/// its light, `random_ticks` lists the rows that get them, `default` is the default row.
/// Read as the [`State`]s it stands for; a file has `states` or this, not both.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct StateTable {
    properties: Vec<(String, Vec<String>)>,
    shapes: Vec<TableShape>,
    rows: Vec<Vec<u32>>,
    #[serde(default)]
    light: BTreeMap<usize, u8>,
    #[serde(default)]
    random_ticks: Vec<usize>,
    #[serde(default)]
    default: Option<usize>,
}

/// A [`StateTable`]'s shape entry: a vanilla state's name (null: the template's), or the
/// boxes of the state's own (`like` the vanilla state it stands in for otherwise).
#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum TableShape {
    Named(Option<String>),
    Own(OwnShapes),
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct OwnShapes {
    #[serde(default)]
    like: Option<String>,
    #[serde(default)]
    outline: Option<Vec<[f64; 6]>>,
    #[serde(default)]
    collision: Option<Vec<[f64; 6]>>,
    #[serde(default)]
    occlusion: Option<Vec<[f64; 6]>>,
}

impl StateTable {
    fn states(self) -> Result<Vec<State>, String> {
        let width = self.properties.len() + 1;
        let mut out = Vec::with_capacity(self.rows.len());
        for row in &self.rows {
            if row.len() != width {
                return Err(format!(
                    "state_table: a row has {width} indices (shape, then one per property)"
                ));
            }
            let entry = self
                .shapes
                .get(row[0] as usize)
                .ok_or("state_table: a row's shape is past shapes")?;
            let (shape, outline, collision, occlusion) = match entry {
                TableShape::Named(n) => (n.clone(), None, None, None),
                TableShape::Own(o) => (
                    o.like.clone(),
                    o.outline.clone(),
                    o.collision.clone(),
                    o.occlusion.clone(),
                ),
            };
            let mut properties = BTreeMap::new();
            for ((name, values), &i) in self.properties.iter().zip(&row[1..]) {
                let v = values
                    .get(i as usize)
                    .ok_or("state_table: a row's value is past its property's values")?;
                properties.insert(name.clone(), v.clone());
            }
            out.push(State {
                properties,
                shape,
                outline,
                collision,
                occlusion,
                light: None,
                random_ticks: None,
                default: false,
            });
        }
        let len = out.len();
        let row = |i: usize| -> Result<usize, String> {
            (i < len)
                .then_some(i)
                .ok_or_else(|| "state_table: names a row past rows".to_owned())
        };
        for (&i, &l) in &self.light {
            out[row(i)?].light = Some(l);
        }
        for &i in &self.random_ticks {
            out[row(i)?].random_ticks = Some(true);
        }
        if let Some(d) = self.default {
            out[row(d)?].default = true;
        }
        Ok(out)
    }
}

/// One of a block's own states, for a block whose properties no vanilla block has (a
/// cooking pot's `facing` and `support`): the template (a block without properties) gives
/// its behaviour, `shape` its collision, outline and occlusion.
#[derive(Clone, Debug, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct State {
    pub properties: BTreeMap<String, String>,
    /// A vanilla state with this state's shapes: `minecraft:cake[bites=1]`; unset, the
    /// template's.
    #[serde(default)]
    pub shape: Option<String>,
    /// Its own outline boxes (`[min x, y, z, max x, y, z]`, block units), in place of
    /// `shape`'s: a ported mod's block whose shape no vanilla state has.
    #[serde(default)]
    pub outline: Option<Vec<[f64; 6]>>,
    /// Its own collision boxes, in place of `shape`'s.
    #[serde(default)]
    pub collision: Option<Vec<[f64; 6]>>,
    /// Its own occlusion boxes (the faces it hides beside it; empty: none), in place of
    /// `shape`'s.
    #[serde(default)]
    pub occlusion: Option<Vec<[f64; 6]>>,
    /// Light emitted, 0 to 15; unset, the block's.
    #[serde(default)]
    pub light: Option<u8>,
    /// Random ticks reach it (a crop's growth); unset, the template's.
    #[serde(default)]
    pub random_ticks: Option<bool>,
    /// The block's default state (else the first).
    #[serde(default)]
    pub default: bool,
}

/// The block file's states: its `states`, or its `state_table` read as them.
pub(super) fn read(states: Vec<State>, table: Option<StateTable>) -> Result<Vec<State>, String> {
    match table {
        Some(_) if !states.is_empty() => Err("states or state_table, not both".into()),
        Some(t) => t.states(),
        None => Ok(states),
    }
}

/// A block's own states are well formed (part of [`check_block`]).
pub(super) fn check(states: &[State]) -> Result<(), String> {
    if let Some(first) = states.first() {
        let mut seen = BTreeSet::new();
        for s in states {
            if s.properties.keys().ne(first.properties.keys()) {
                return Err("states: every state has the same properties".into());
            }
            if !seen.insert(&s.properties) {
                return Err("states: a state is listed twice".into());
            }
            if s.shape
                .as_deref()
                .is_some_and(|v| !vanilla_id(v.split('[').next().unwrap_or(v)))
            {
                return Err("states: shape names a vanilla state (minecraft:...)".into());
            }
            if s.light.is_some_and(|l| l > 15) {
                return Err("states: light is 0 to 15".into());
            }
        }
        if states.iter().filter(|s| s.default).count() > 1 {
            return Err("states: one default".into());
        }
    }
    Ok(())
}
