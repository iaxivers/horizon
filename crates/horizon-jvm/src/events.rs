//! Horizon's game events for Java mods, and what mods ask back.
//!
//! Once per server tick the game hands [`server_tick`] the players on the server and what they
//! did since the last tick. The bridge's platform mod (`java/platform/`, the builtin mod
//! `horizon`) mirrors them into vanilla objects (a `MinecraftServer`, `ServerLevel`s,
//! `ServerPlayer`s made without their constructors) and fires Fabric API's events with them:
//! server start and tick, level tick, connection init, join and disconnect, entity load, chat,
//! block break.
//!
//! What mods ask for comes back as [`Action`]s through the natives (`Natives.tell`,
//! `Natives.broadcast`), queued here and taken by the game with [`take_actions`] after the
//! call, on the server thread, where it applies them as server mods' output.

use jni::objects::{GlobalRef, JClass, JObject, JValue};
use jni::{JNIEnv, sys};
use std::sync::{Mutex, OnceLock};

/// A player on the server, as the game sees it this tick.
#[derive(Clone, Debug, PartialEq)]
pub struct Player {
    /// `UUID.toString()`.
    pub uuid: String,
    pub name: String,
    pub position: [f64; 3],
    pub yaw: f32,
    pub pitch: f32,
    /// `minecraft:overworld`, ...
    pub dimension: String,
    /// Vanilla's game mode ids: 0 survival, 1 creative, 2 adventure, 3 spectator.
    pub game_mode: i32,
    pub health: f32,
    /// Vanilla's permission level, 0 (everyone) to 4 (owner): what the player's commands may do.
    pub permission: u8,
    /// The inventory, when it changed since the last tick (None: as it was): the
    /// [`INVENTORY_SLOTS`] slots in vanilla's `Inventory` order (hotbar 0-8, the rest 9-35,
    /// feet, legs, chest, head 36-39, off hand 40), each an item stack as vanilla's
    /// `ItemStack.CODEC` writes it in SNBT, `""` for an empty slot.
    pub inventory: Option<Vec<String>>,
}

/// The slots of a player's inventory the mods see (vanilla's `Inventory`).
pub const INVENTORY_SLOTS: usize = 41;

/// Something a player did since the last tick.
#[derive(Clone, Debug, PartialEq)]
pub enum Event {
    Joined {
        uuid: String,
    },
    Left {
        uuid: String,
    },
    Chat {
        uuid: String,
        text: String,
    },
    /// A block a player broke or placed, with its state as `minecraft:oak_log[axis=y]` (for a
    /// break, the state before).
    Block {
        uuid: String,
        position: [i32; 3],
        state: String,
        placed: bool,
    },
}

/// What a mod asked of the game.
#[derive(Clone, Debug, PartialEq)]
pub enum Action {
    /// `ServerPlayer.sendSystemMessage`: a line to one player.
    Tell { uuid: String, text: String },
    /// `PlayerList.broadcastSystemMessage`: a line to every player, and the server log.
    Broadcast { text: String },
    /// `ServerPlayer.teleport` and the connection's: the player is to be moved there.
    Teleport {
        uuid: String,
        /// `minecraft:overworld`, ...
        dimension: String,
        position: [f64; 3],
        yaw: f32,
        pitch: f32,
    },
    /// `ServerPlayer.onUpdateAbilities`: the player's abilities (`/fly`, `/invuln`), as
    /// vanilla computed them in that game mode.
    Abilities {
        uuid: String,
        /// The game mode the Java side had the player in (`GameType.getId`).
        game_mode: i32,
        /// invulnerable 1, flying 2, may fly 4, instabuild 8.
        flags: i8,
        flying_speed: f32,
        walking_speed: f32,
    },
    /// ClientboundPlayerInfoUpdatePacket's UPDATE_DISPLAY_NAME: the name a player shows in
    /// the tab list (`ServerPlayer.getTabListDisplayName`), as a JSON text component; None
    /// for the player's own name.
    TabName { uuid: String, json: Option<String> },
    /// `ServerPlayer.openMenu`: a mod opened a menu for the player (`/workbench`). Horizon
    /// opens its own menu of that type, when it has one.
    OpenMenu {
        uuid: String,
        /// The `MenuType`'s id, `minecraft:crafting`, ...
        menu: String,
        /// The title, a JSON text component.
        title: Option<String>,
    },
    /// `Level.setBlock` on a mirrored level: the block is to be that state (`/setblock`).
    SetBlock {
        dimension: String,
        position: [i32; 3],
        /// `minecraft:oak_log[axis=y]`.
        state: String,
    },
    /// `ServerLevel.addFreshEntity` of an item (a block's drops): an item entity there.
    DropItem {
        dimension: String,
        position: [f64; 3],
        velocity: [f64; 3],
        /// `minecraft:oak_log`.
        item: String,
        count: i32,
    },
    /// `ServerLevel.levelEvent`: a level event there (`LevelEvent`, 2001 a block's break
    /// particles and sound), as Horizon's level events go.
    LevelEvent {
        dimension: String,
        id: i32,
        position: [i32; 3],
        data: i32,
        /// For the events whose data is a block state (2001): that state, by name.
        state: Option<String>,
    },
    /// A mod changed slot `slot` of a player's inventory (vanilla's `Inventory` order, as in
    /// [`Player::inventory`]): the item stack in SNBT, None for empty.
    SetSlot {
        uuid: String,
        slot: usize,
        item: Option<String>,
    },
}

static ACTIONS: Mutex<Vec<Action>> = Mutex::new(Vec::new());
/// At most this many actions wait; a mod flooding them loses the oldest.
const MAX_ACTIONS: usize = 4096;
/// `dev.horizonlab.platform.HorizonServer`, through Fabric's class loader.
static SERVER_CLASS: OnceLock<Option<GlobalRef>> = OnceLock::new();

pub(crate) fn push(action: Action) {
    let mut actions = ACTIONS.lock().unwrap_or_else(|e| e.into_inner());
    if actions.len() >= MAX_ACTIONS {
        actions.remove(0);
    }
    actions.push(action);
}

/// The actions mods asked for since the last call, oldest first.
pub fn take_actions() -> Vec<Action> {
    std::mem::take(&mut *ACTIONS.lock().unwrap_or_else(|e| e.into_inner()))
}

/// Whether the platform mod runs: Fabric started and found `HorizonServer`.
pub fn enabled() -> bool {
    crate::get().is_some_and(|b| b.fabric()) && server_class().is_some()
}

fn server_class() -> Option<&'static GlobalRef> {
    SERVER_CLASS
        .get_or_init(|| {
            let bridge = crate::get()?;
            let mut env = bridge.vm().attach_current_thread_permanently().ok()?;
            match bridge.find_class(&mut env, "dev/horizonlab/platform/HorizonServer") {
                Ok(class) => env.new_global_ref(class).ok(),
                Err(_) => {
                    let _ = env.exception_clear();
                    log::warn!("Java mods: no platform mod, so no game events for mods");
                    None
                }
            }
        })
        .as_ref()
}

fn optional_strings<'local>(
    env: &mut JNIEnv<'local>,
    items: &[Option<&str>],
) -> jni::errors::Result<JObject<'local>> {
    let array = env.new_object_array(
        items.len() as sys::jsize,
        "java/lang/String",
        JObject::null(),
    )?;
    for (i, item) in items.iter().enumerate() {
        if let Some(item) = item {
            let s = env.new_string(item)?;
            env.set_object_array_element(&array, i as sys::jsize, &s)?;
            env.delete_local_ref(s)?;
        }
    }
    Ok(array.into())
}

fn strings<'local>(
    env: &mut JNIEnv<'local>,
    items: &[&str],
) -> jni::errors::Result<JObject<'local>> {
    let array = env.new_object_array(
        items.len() as sys::jsize,
        "java/lang/String",
        JObject::null(),
    )?;
    for (i, item) in items.iter().enumerate() {
        let s = env.new_string(item)?;
        env.set_object_array_element(&array, i as sys::jsize, &s)?;
        env.delete_local_ref(s)?;
    }
    Ok(array.into())
}

/// Hands the tick to the platform mod, which fires Fabric API's events. Call it on the server
/// thread at the tick's end; then apply [`take_actions`]. An exception a mod throws is logged
/// by the platform mod and never stops the tick.
pub fn server_tick(
    tick: i64,
    dedicated: bool,
    world: &std::path::Path,
    players: &[Player],
    events: &[Event],
) -> Result<(), String> {
    let Some(class) = server_class() else {
        return Ok(());
    };
    let bridge = crate::get().ok_or("the JVM is not running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    // Local references made here are freed when the frame pops.
    let result = env.with_local_frame(64, |env| -> jni::errors::Result<()> {
        call_tick(env, class, tick, dedicated, world, players, events)
    });
    result.map_err(|e| crate::describe(&mut env, e))
}

fn call_tick(
    env: &mut JNIEnv,
    class: &GlobalRef,
    tick: i64,
    dedicated: bool,
    world: &std::path::Path,
    players: &[Player],
    events: &[Event],
) -> jni::errors::Result<()> {
    let world = env.new_string(world.to_string_lossy())?;
    let ids: Vec<&str> = players.iter().map(|p| p.uuid.as_str()).collect();
    let names: Vec<&str> = players.iter().map(|p| p.name.as_str()).collect();
    let dims: Vec<&str> = players.iter().map(|p| p.dimension.as_str()).collect();
    let mut motion = Vec::with_capacity(players.len() * 5);
    for p in players {
        motion.extend_from_slice(&[
            p.position[0],
            p.position[1],
            p.position[2],
            f64::from(p.yaw),
            f64::from(p.pitch),
        ]);
    }
    let modes: Vec<i32> = players.iter().map(|p| p.game_mode).collect();
    let health: Vec<f32> = players.iter().map(|p| p.health).collect();
    let permissions: Vec<i32> = players.iter().map(|p| i32::from(p.permission)).collect();
    // INVENTORY_SLOTS per player; nulls for a player whose inventory didn't change.
    let mut inventories: Vec<Option<&str>> = Vec::with_capacity(players.len() * INVENTORY_SLOTS);
    for p in players {
        match &p.inventory {
            Some(slots) => inventories.extend(
                (0..INVENTORY_SLOTS).map(|i| Some(slots.get(i).map_or("", String::as_str))),
            ),
            None => inventories.extend(std::iter::repeat_n(None, INVENTORY_SLOTS)),
        }
    }

    // Events as parallel arrays: a kind per event (0 joined, 1 left, 2 chat, 3 broken,
    // 4 placed), its player, a text (chat line or block state) and a position.
    let mut kinds = Vec::with_capacity(events.len());
    let mut who = Vec::with_capacity(events.len());
    let mut texts = Vec::with_capacity(events.len());
    let mut positions = Vec::with_capacity(events.len() * 3);
    for event in events {
        let (kind, uuid, text, pos) = match event {
            Event::Joined { uuid } => (0, uuid, "", [0; 3]),
            Event::Left { uuid } => (1, uuid, "", [0; 3]),
            Event::Chat { uuid, text } => (2, uuid, text.as_str(), [0; 3]),
            Event::Block {
                uuid,
                position,
                state,
                placed,
            } => (if *placed { 4 } else { 3 }, uuid, state.as_str(), *position),
        };
        kinds.push(kind);
        who.push(uuid.as_str());
        texts.push(text);
        positions.extend_from_slice(&pos);
    }

    let ids = strings(env, &ids)?;
    let names = strings(env, &names)?;
    let dims = strings(env, &dims)?;
    let motion_array = env.new_double_array(motion.len() as sys::jsize)?;
    env.set_double_array_region(&motion_array, 0, &motion)?;
    let modes_array = env.new_int_array(modes.len() as sys::jsize)?;
    env.set_int_array_region(&modes_array, 0, &modes)?;
    let health_array = env.new_float_array(health.len() as sys::jsize)?;
    env.set_float_array_region(&health_array, 0, &health)?;
    let permissions_array = env.new_int_array(permissions.len() as sys::jsize)?;
    env.set_int_array_region(&permissions_array, 0, &permissions)?;
    let kinds_array = env.new_int_array(kinds.len() as sys::jsize)?;
    env.set_int_array_region(&kinds_array, 0, &kinds)?;
    let who = strings(env, &who)?;
    let texts = strings(env, &texts)?;
    let positions_array = env.new_int_array(positions.len() as sys::jsize)?;
    env.set_int_array_region(&positions_array, 0, &positions)?;
    let inventories = optional_strings(env, &inventories)?;

    let class = <&JClass>::from(class.as_obj());
    env.call_static_method(
        class,
        "tick",
        "(JZLjava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[D[I[F[I[I[Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;)V",
        &[
            JValue::Long(tick),
            JValue::Bool(dedicated.into()),
            JValue::Object(&world),
            JValue::Object(&ids),
            JValue::Object(&names),
            JValue::Object(&dims),
            JValue::Object(&motion_array),
            JValue::Object(&modes_array),
            JValue::Object(&health_array),
            JValue::Object(&permissions_array),
            JValue::Object(&kinds_array),
            JValue::Object(&who),
            JValue::Object(&texts),
            JValue::Object(&positions_array),
            JValue::Object(&inventories),
        ],
    )?;
    Ok(())
}

/// Runs `f` on this thread's JNI env with the platform's `HorizonServer` class, in a local frame.
fn with_server<T>(
    f: impl FnOnce(&mut JNIEnv, &JClass) -> jni::errors::Result<T>,
) -> Result<Option<T>, String> {
    let Some(class) = server_class() else {
        return Ok(None);
    };
    let bridge = crate::get().ok_or("the JVM is not running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let result = env.with_local_frame(32, |env| {
        let class = <&JClass>::from(class.as_obj());
        f(env, class)
    });
    result.map(Some).map_err(|e| crate::describe(&mut env, e))
}

fn string_array(env: &mut JNIEnv, array: JObject) -> jni::errors::Result<Vec<String>> {
    let array = jni::objects::JObjectArray::from(array);
    let len = env.get_array_length(&array)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let item = jni::objects::JString::from(env.get_object_array_element(&array, i)?);
        out.push(env.get_string(&item)?.into());
        env.delete_local_ref(item)?;
    }
    Ok(out)
}

/// Every command the server's dispatcher has (vanilla's and the mods'), by name, sorted.
pub fn command_names() -> Result<Vec<String>, String> {
    let names = with_server(|env, class| {
        let names = env
            .call_static_method(class, "commandNames", "()[Ljava/lang/String;", &[])?
            .l()?;
        string_array(env, names)
    })?;
    Ok(names.unwrap_or_default())
}

/// These commands as a root of the commands report (Horizon's command tree format), as JSON.
pub fn command_tree(names: &[String]) -> Result<String, String> {
    let tree = with_server(|env, class| {
        let refs: Vec<&str> = names.iter().map(String::as_str).collect();
        let names = strings(env, &refs)?;
        let tree = env
            .call_static_method(
                class,
                "commandTree",
                "([Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&names)],
            )?
            .l()?;
        Ok(String::from(
            env.get_string(&jni::objects::JString::from(tree))?,
        ))
    })?;
    Ok(tree.unwrap_or_else(|| r#"{"type":"root","children":{}}"#.into()))
}

/// Runs a command line (no slash) as the player with this UUID; what it says comes back as
/// [`Action`]s. False when that player isn't on the mirrored server.
pub fn run_command(uuid: &str, line: &str) -> Result<bool, String> {
    let ran = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let line = env.new_string(line)?;
        env.call_static_method(
            class,
            "command",
            "(Ljava/lang/String;Ljava/lang/String;)Z",
            &[JValue::Object(&uuid), JValue::Object(&line)],
        )?
        .z()
    })?;
    Ok(ran.unwrap_or(false))
}

/// `PlayerBlockBreakEvents.BEFORE`: player `uuid` is about to break `state` at `position` of
/// `dimension`. False when a mod cancels the break. Run it inside [`crate::world::with_blocks`]
/// so mods read the world as it is; what they change comes back as actions.
pub fn before_break(
    uuid: &str,
    dimension: &str,
    position: [i32; 3],
    state: &str,
) -> Result<bool, String> {
    let [x, y, z] = position;
    let allowed = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let state = env.new_string(state)?;
        env.call_static_method(
            class,
            "beforeBreak",
            "(Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;)Z",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(y),
                JValue::Int(z),
                JValue::Object(&state),
            ],
        )?
        .z()
    })?;
    Ok(allowed.unwrap_or(true))
}
