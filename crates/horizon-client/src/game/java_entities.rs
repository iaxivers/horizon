//! Java mods' entities (a Naturalist bear): they live and tick in horizon-jvm's mirrored
//! levels (`HorizonEntities`), and their own renderers, run in the headless client, say each
//! tick what to draw (`HorizonEntityRender`): models as part trees, with their parts' poses,
//! matrices, textures, tints and overlays. The server sends each player those near it on
//! [`CHANNEL`] (game/server_mods.rs); this game draws them with its entity pass, and aims at
//! them: an attack or a use of one goes to the server as vanilla's packets do, which hands
//! it to the mods.
use super::Game;
use horizon_client::mesher::Vertex;
use horizon_client::mobs::{Part, anim::Poses, render};
use horizon_core::geometry::{Aabb, Vec3};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;

/// Server to client, each tick: `{"entities": [...], "models": {key: tree}}`
/// (`HorizonEntityRender.snapshot`'s entities of the player's level, near it, and the trees
/// this client has not had yet).
pub(super) const CHANNEL: &str = "horizon:java_entities";

/// Entity ids the bridge gives Java's entities (`HorizonServer.nextEntityId`), apart from
/// Horizon's own.
pub(super) const FIRST_ID: i32 = 0x7fff_0000;

/// One model an entity's renderer submitted.
#[derive(Clone)]
struct Drawn {
    key: String,
    /// The atlas sprite of its texture.
    sprite: String,
    /// The pose stack's matrix, relative to the entity's position (column-major).
    matrix: [f32; 16],
    /// Java's ARGB tint.
    tint: u32,
    /// Java's packed overlay (OverlayTexture: u white, v red).
    overlay: u32,
    /// Parts' poses that differ from their initial ones, by path.
    poses: HashMap<String, [f32; 9]>,
    hidden: Vec<String>,
    skip: Vec<String>,
}

/// An item its renderer submitted (held, worn on the head): the stack, how it is shown and
/// the pose stack's matrix then, relative to the entity's position.
#[derive(Clone)]
struct Item {
    stack: horizon_client::items::ItemStack,
    context: horizon_assets::item_model::DisplayContext,
    matrix: [f32; 16],
}

#[derive(Clone)]
struct Shown {
    id: i32,
    position: [f64; 3],
    width: f64,
    height: f64,
    models: Vec<Drawn>,
    items: Vec<Item>,
    /// Its custom name, and whether it always shows (`isCustomNameVisible`).
    name: Option<horizon_client::text::Text>,
    name_visible: bool,
    /// The shadow's radius its renderer gives (0: none).
    shadow: f32,
    /// A mod's block entity its renderer draws (a cutting board's item), at its block's
    /// corner: no box to hit, drawn as block entities are (their range, the light above).
    block: bool,
}

/// The Java entities this game was last told of, and the tick before (to move between them).
#[derive(Default)]
pub(super) struct JavaEntities {
    trees: HashMap<String, Arc<Part>>,
    now: Vec<Shown>,
    before: HashMap<i32, Shown>,
}

/// The atlas key of an entity texture: `ns:textures/entity/x.png` is `ns:entity/x`
/// (`HudAtlas` packs mods' `textures/entity/` under their namespace), vanilla's `entity/x`.
fn sprite_key(id: &str) -> String {
    let (ns, path) = id.split_once(':').unwrap_or(("minecraft", id));
    let path = path
        .trim_start_matches("textures/")
        .trim_end_matches(".png");
    if ns == "minecraft" {
        path.to_owned()
    } else {
        format!("{ns}:{path}")
    }
}

fn floats<const N: usize>(v: &Value) -> Option<[f32; N]> {
    let a = v.as_array()?;
    (a.len() == N).then(|| std::array::from_fn(|i| a[i].as_f64().unwrap_or(0.0) as f32))
}

fn strings(v: &Value) -> Vec<String> {
    v.as_array()
        .into_iter()
        .flatten()
        .filter_map(|s| s.as_str().map(str::to_owned))
        .collect()
}

fn drawn(v: &Value) -> Option<Drawn> {
    Some(Drawn {
        key: v.get("key")?.as_str()?.to_owned(),
        sprite: sprite_key(v.get("tex")?.as_str()?),
        matrix: floats(v.get("m")?)?,
        tint: v.get("tint").and_then(Value::as_i64).unwrap_or(-1) as u32,
        overlay: v.get("overlay").and_then(Value::as_i64).unwrap_or(0) as u32,
        poses: v
            .get("poses")
            .and_then(Value::as_object)
            .into_iter()
            .flatten()
            .filter_map(|(k, p)| Some((k.clone(), floats(p)?)))
            .collect(),
        hidden: v.get("hidden").map(strings).unwrap_or_default(),
        skip: v.get("skip").map(strings).unwrap_or_default(),
    })
}

fn item(v: &Value) -> Option<Item> {
    let (tag, _) = horizon_world::nbt::parse_snbt(v.get("item")?.as_str()?).ok()?;
    Some(Item {
        stack: horizon_items::item_nbt::item_from_nbt(&tag)?,
        context: horizon_assets::item_model::DisplayContext::by_name(v.get("ctx")?.as_str()?)?,
        matrix: floats(v.get("m")?)?,
    })
}

fn shown(v: &Value) -> Option<Shown> {
    let f = |k: &str| v.get(k).and_then(Value::as_f64);
    Some(Shown {
        items: v
            .get("items")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(item)
            .collect(),
        name: v.get("name").map(|n| {
            horizon_client::component::to_text(n, &mut |k| horizon_client::text::Text::literal(k))
        }),
        name_visible: v
            .get("name_visible")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        shadow: f("shadow").unwrap_or(0.0) as f32,
        block: v.get("block").and_then(Value::as_bool).unwrap_or(false),
        id: v.get("id")?.as_i64()? as i32,
        position: [f("x")?, f("y")?, f("z")?],
        width: f("w").unwrap_or(0.6),
        height: f("h").unwrap_or(1.8),
        models: v
            .get("models")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(drawn)
            .collect(),
    })
}

/// Java's ARGB as a vertex colour (ABGR).
fn abgr(argb: u32) -> u32 {
    let (a, r, g, b) = (
        argb >> 24,
        (argb >> 16) & 255,
        (argb >> 8) & 255,
        argb & 255,
    );
    a << 24 | b << 16 | g << 8 | r
}

/// Java's packed overlay as Horizon's overlay code: v 3 (OverlayTexture.RED_OVERLAY_V) is the
/// hurt flash, u the white flash's column (a creeper's swelling).
fn overlay_code(packed: u32) -> u32 {
    let (u, v) = (packed & 0xFFFF, packed >> 16);
    if v < 8 {
        1
    } else if u == 0 {
        0
    } else {
        horizon_client::overlay::WHITE + u.min(15)
    }
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

impl JavaEntities {
    /// A tick's entities from the server.
    fn receive(&mut self, data: &[u8]) {
        let Ok(v) = serde_json::from_slice::<Value>(data) else {
            return;
        };
        for (key, tree) in v
            .get("models")
            .and_then(Value::as_object)
            .into_iter()
            .flatten()
        {
            self.trees
                .insert(key.clone(), Arc::new(Part::parse("root", tree)));
        }
        let now: Vec<Shown> = v
            .get("entities")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(shown)
            .collect();
        self.before = std::mem::replace(&mut self.now, now)
            .into_iter()
            .map(|s| (s.id, s))
            .collect();
    }

    /// Where an entity is `alpha` of the way from the last tick to this one.
    fn position(&self, s: &Shown, alpha: f64) -> [f64; 3] {
        let Some(b) = self.before.get(&s.id) else {
            return s.position;
        };
        std::array::from_fn(|i| b.position[i] + (s.position[i] - b.position[i]) * alpha)
    }

    fn bounds(&self, s: &Shown, alpha: f64) -> Aabb {
        let [x, y, z] = self.position(s, alpha);
        let half = s.width / 2.0;
        Aabb::new(
            Vec3::new(x - half, y, z - half),
            Vec3::new(x + half, y + s.height, z + half),
        )
    }
}

impl Game<'_> {
    /// The server's Java entities of this tick ([`CHANNEL`]).
    pub(super) fn remote_java_entities(&mut self, data: &[u8]) {
        self.java_entities.receive(data);
    }

    /// The Java entities for the entity pass: each model its renderer submitted, posed as it
    /// posed it, between the last two ticks.
    pub(super) fn java_entity_geometry(
        &self,
        camera: glam::DVec3,
        alpha: f64,
        out: &mut Vec<Vertex>,
    ) {
        let java = &self.java_entities;
        if java.now.is_empty() {
            return;
        }
        let atlas = &self.gui.atlas;
        let t = alpha as f32;
        for s in &java.now {
            let at = java.position(s, alpha);
            let d = glam::DVec3::from_array(at) - camera;
            // Entity.shouldRenderAtSqrDistance: 64 times the box's mean edge; a block entity:
            // BlockEntityRenderer.getViewDistance's 64 blocks.
            let range = if s.block {
                64.0
            } else {
                (s.width * 2.0 + s.height) / 3.0 * 64.0 * self.view_scale()
            };
            if d.length_squared() >= range * range {
                continue;
            }
            // A block entity's light: the block above it (a full block's own is dark inside,
            // and what its renderer draws sits on top: a stove's grill).
            let lit_y = if s.block {
                at[1] + 1.0
            } else {
                at[1] + s.height * 0.85
            };
            let light = self.world.light(
                at[0].floor() as i32,
                lit_y.floor() as i32,
                at[2].floor() as i32,
            );
            let packed = (u32::from(light >> 4) * 16) | ((u32::from(light & 15) * 16) << 8);
            let before = java.before.get(&s.id);
            for (i, m) in s.models.iter().enumerate() {
                let Some(tree) = java.trees.get(&m.key) else {
                    continue;
                };
                let Some(r) = atlas.sprites.get(&m.sprite) else {
                    continue;
                };
                // The same model the tick before: its matrix and poses, to move from.
                let last = before
                    .and_then(|b| b.models.get(i))
                    .filter(|b| b.key == m.key);
                let matrix: [f32; 16] = match last {
                    Some(b) => std::array::from_fn(|k| lerp(b.matrix[k], m.matrix[k], t)),
                    None => m.matrix,
                };
                let mut poses = Poses::new(tree);
                for (path, pose) in &m.poses {
                    let from = last.and_then(|b| b.poses.get(path));
                    *poses.get(path) = match from {
                        Some(f) => std::array::from_fn(|k| lerp(f[k], pose[k], t)),
                        None => *pose,
                    };
                }
                // A part posed last tick and back to its initial pose now.
                if let Some(b) = last {
                    for (path, f) in &b.poses {
                        if !m.poses.contains_key(path) && poses.has(path) {
                            let initial = *poses.get(path);
                            *poses.get(path) = std::array::from_fn(|k| lerp(f[k], initial[k], t));
                        }
                    }
                }
                for path in &m.hidden {
                    poses.visible(path, false);
                }
                for path in &m.skip {
                    poses.skip_draw(path);
                }
                let base = glam::Mat4::from_translation(d.as_vec3())
                    * glam::Mat4::from_cols_array(&matrix);
                render::draw(
                    tree,
                    base,
                    &poses,
                    [r.u0, r.v0, r.u1, r.v1],
                    abgr(m.tint),
                    packed,
                    overlay_code(m.overlay),
                    out,
                );
            }
            // ItemInHandLayer and the like: the items, as Horizon's item renderer draws them
            // (ItemModelResolver.updateForLiving's seed: the entity id plus the context).
            for item in &s.items {
                let seed = s.id.wrapping_add(item.context as i32);
                let state = self.resolve_item(&item.stack, item.context, None, seed);
                let pose = glam::Mat4::from_translation(d.as_vec3())
                    * glam::Mat4::from_cols_array(&item.matrix);
                self.submit_item_world(&state, pose, packed, out);
            }
        }
    }

    /// Where each Java entity's shadow is (between the last two ticks) and its radius.
    pub(super) fn java_entity_shadows(&self, alpha: f64) -> Vec<(glam::DVec3, f32)> {
        let java = &self.java_entities;
        java.now
            .iter()
            .filter(|s| s.shadow > 0.0 && !s.block)
            .map(|s| (glam::DVec3::from_array(java.position(s, alpha)), s.shadow))
            .collect()
    }

    /// The Java entities with a custom name: id, position, height, the name and whether it
    /// always shows.
    pub(super) fn java_entity_names(
        &self,
        alpha: f64,
    ) -> Vec<(i32, glam::DVec3, f64, horizon_client::text::Text, bool)> {
        let java = &self.java_entities;
        java.now
            .iter()
            .filter_map(|s| {
                let name = s.name.clone()?;
                let at = glam::DVec3::from_array(java.position(s, alpha));
                Some((s.id, at, s.height, name, s.name_visible))
            })
            .collect()
    }

    /// The Java entity the crosshair is on, nearer than `reach`: its id and distance.
    pub(super) fn java_entity_hit(&self) -> Option<(i32, f64)> {
        let eye = self.eye_position()?;
        let dir = super::view_vector(self.yaw, self.pitch);
        let reach: f64 = if self.creative { 5.0 } else { 3.0 };
        let java = &self.java_entities;
        java.now
            .iter()
            .filter(|s| !s.block)
            .filter_map(|s| Some((s.id, super::ray_box(eye, dir, reach, java.bounds(s, 1.0))?)))
            .min_by(|a, b| a.1.total_cmp(&b.1))
    }

    /// Where on the Java entity with this id the crosshair is, relative to its position (the
    /// interact packet's location).
    pub(super) fn java_entity_location(&self, id: i32, distance: f64) -> [f64; 3] {
        let (Some(eye), Some(s)) = (
            self.eye_position(),
            self.java_entities.now.iter().find(|s| s.id == id),
        ) else {
            return [0.0; 3];
        };
        let dir = super::view_vector(self.yaw, self.pitch);
        std::array::from_fn(|i| eye[i] + dir[i] * distance - s.position[i])
    }
}
