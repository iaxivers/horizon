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

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
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
    /// `FoodData`'s food level and saturation.
    pub food: i32,
    pub saturation: f32,
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
    /// A player died (its health reached 0): `cause` is the damage type that killed it
    /// (`minecraft:fall`, ...). The game has dropped what it drops by then.
    Died {
        uuid: String,
        cause: String,
    },
    /// A dead player respawned.
    Respawned {
        uuid: String,
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
    /// A mod's menu the player has open, which Horizon shows and Java runs (a cabinet's):
    /// `open` (the drawn menu type and title) when it opens, its slots' items as SNBT and the
    /// carried one; `items` None when it closed.
    MenuContent {
        uuid: String,
        open: Option<(String, Option<String>)>,
        items: Option<Vec<Option<String>>>,
        carried: Option<String>,
    },
    /// A mod changed a vanilla block's block entity (a storage mod took from a chest): it is
    /// now this, as vanilla saves it (`saveWithFullMetadata`), in SNBT.
    SetBlockEntity {
        dimension: String,
        position: [i32; 3],
        snbt: String,
    },
    /// A frame of the player's open mod screen, drawn by the mod's own client code in the
    /// headless client: its operations as JSON (`HorizonGraphics`), for the player's game to draw.
    ScreenFrame { uuid: String, frame: String },
    /// A mod fed (or starved) a player: its `FoodData` now (a pie's slice).
    Food {
        uuid: String,
        food: i32,
        saturation: f32,
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
/// Queues an action again (one the game took but leaves for later).
pub fn push_action(action: Action) {
    push(action);
}

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
    let food: Vec<f32> = players
        .iter()
        .flat_map(|p| [p.food as f32, p.saturation])
        .collect();
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
    // 4 placed, 5 died, 6 respawned), its player, a text (chat line or block state) and a position.
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
            Event::Died { uuid, cause } => (5, uuid, cause.as_str(), [0; 3]),
            Event::Respawned { uuid } => (6, uuid, "", [0; 3]),
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
    let food_array = env.new_float_array(food.len() as sys::jsize)?;
    env.set_float_array_region(&food_array, 0, &food)?;
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
        "(JZLjava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[D[I[F[F[I[I[Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;)V",
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
            JValue::Object(&food_array),
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

/// What mods made of a player's use of a block ([`use_block`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UseResult {
    /// No mod took the click: the game uses the block.
    Pass,
    /// A mod took it (UseBlockCallback's SUCCESS, FAIL, ...): the game does nothing more.
    Taken,
    /// A mod took it, and the player's arm swings.
    TakenSwing,
}

/// A player uses (right-clicks) a block: vanilla's `ServerPlayerGameMode.useItemOn` up to the
/// end of the mods' injections at its head (Fabric API's `UseBlockCallback`). `face` is
/// vanilla's `Direction` id (0 down, 1 up, 2 north, 3 south, 4 west, 5 east), `hand` 0 main,
/// 1 off, `selected` the hotbar slot. Run it inside [`crate::world::with_blocks`].
#[allow(clippy::too_many_arguments)]
pub fn use_block(
    uuid: &str,
    dimension: &str,
    position: [i32; 3],
    face: i32,
    hit: [f64; 3],
    hand: i32,
    selected: i32,
) -> Result<UseResult, String> {
    let [x, y, z] = position;
    let result = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        env.call_static_method(
            class,
            "useBlock",
            "(Ljava/lang/String;Ljava/lang/String;IIIIDDDII)I",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(y),
                JValue::Int(z),
                JValue::Int(face),
                JValue::Double(hit[0]),
                JValue::Double(hit[1]),
                JValue::Double(hit[2]),
                JValue::Int(hand),
                JValue::Int(selected),
            ],
        )?
        .i()
    })?;
    Ok(match result {
        Some(2) => UseResult::TakenSwing,
        Some(1) => UseResult::Taken,
        _ => UseResult::Pass,
    })
}

/// Random ticks that reached Java mods' blocks in `dimension` (`ServerLevel.tickChunk`'s):
/// each still a mod's block that ticks randomly runs `BlockState.randomTick`.
pub fn random_ticks(dimension: &str, positions: &[[i32; 3]]) -> Result<(), String> {
    let flat: Vec<i32> = positions.iter().flatten().copied().collect();
    with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(flat.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, &flat)?;
        env.call_static_method(
            class,
            "randomTicks",
            "(Ljava/lang/String;[I)V",
            &[JValue::Object(&dimension), JValue::Object(&array)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Input to the player's open mod screen (JSON: `size`, `move`, `press`, `release`, `drag`,
/// `scroll`, `key`, `char`), run by the screen's own code.
pub fn screen_input(uuid: &str, input: &str) -> Result<(), String> {
    let class = client_class().ok_or("no headless client")?;
    let bridge = crate::get().ok_or("Java isn't running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| e.to_string())?;
    env.with_local_frame(8, |env| -> jni::errors::Result<()> {
        let uuid = env.new_string(uuid)?;
        let input = env.new_string(input)?;
        let class = <&JClass>::from(class.as_obj());
        env.call_static_method(
            class,
            "input",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&uuid), JValue::Object(&input)],
        )?;
        Ok(())
    })
    .map_err(|e| crate::describe(&mut env, e))
}

/// The platform's `HorizonClient` (the headless client), when Fabric runs its client side.
fn client_class() -> Option<&'static GlobalRef> {
    static CLIENT_CLASS: std::sync::OnceLock<Option<GlobalRef>> = std::sync::OnceLock::new();
    CLIENT_CLASS
        .get_or_init(|| {
            let bridge = crate::get()?;
            let mut env = bridge.vm().attach_current_thread_permanently().ok()?;
            let class = bridge
                .find_class(&mut env, "dev/horizonlab/platform/HorizonClient")
                .ok()?;
            env.new_global_ref(class).ok()
        })
        .as_ref()
}

/// A click in the window of a mod's menu ([`Action::MenuContent`]): `ServerboundContainerClickPacket`'s
/// slot, button and input, run by the menu.
pub fn menu_click(uuid: &str, slot: i32, button: i32, input: i32) -> Result<(), String> {
    with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        env.call_static_method(
            class,
            "menuClick",
            "(Ljava/lang/String;III)V",
            &[
                JValue::Object(&uuid),
                JValue::Int(slot),
                JValue::Int(button),
                JValue::Int(input),
            ],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// The player closed the window of a mod's menu.
pub fn menu_close(uuid: &str) -> Result<(), String> {
    with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        env.call_static_method(
            class,
            "menuClose",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&uuid)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// The head of a player's `hurtServer`: Fabric API's `ALLOW_DAMAGE`, then the mods'
/// injections at the heads of `ServerPlayer`, `Player` and `LivingEntity.hurtServer`. The
/// amount to hurt by, or None when a mod stops the hit.
pub fn hurt(uuid: &str, damage_type: &str, amount: f32) -> Result<Option<f32>, String> {
    let result = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let damage_type = env.new_string(damage_type)?;
        env.call_static_method(
            class,
            "hurt",
            "(Ljava/lang/String;Ljava/lang/String;F)F",
            &[
                JValue::Object(&uuid),
                JValue::Object(&damage_type),
                JValue::Float(amount),
            ],
        )?
        .f()
    })?;
    Ok(match result {
        Some(a) if a < 0.0 => None,
        Some(a) => Some(a),
        None => Some(amount),
    })
}

/// Writes the oracle facts of the Java mods' blocks and items (and vanilla's, for templates)
/// to `out` (`HorizonContent.exportFacts`): the namespaces the mods register in.
pub fn export_facts(out: &std::path::Path) -> Result<Vec<String>, String> {
    let bridge = crate::get().ok_or("the JVM is not running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let result = env.with_local_frame(16, |env| -> jni::errors::Result<String> {
        let name = env.new_string("dev.horizonlab.platform.HorizonContent")?;
        let class = env
            .call_static_method(
                "dev/horizonlab/bridge/fabric/FabricBoot",
                "gameClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                &[JValue::Object(&name)],
            )?
            .l()?;
        let out = env.new_string(out.to_string_lossy())?;
        let namespaces = env
            .call_static_method(
                JClass::from(class),
                "exportFacts",
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&out)],
            )?
            .l()?;
        Ok(env.get_string(&JString::from(namespaces))?.into())
    });
    let namespaces = result.map_err(|e| crate::describe(&mut env, e))?;
    Ok(namespaces
        .split(',')
        .filter(|n| !n.is_empty())
        .map(str::to_string)
        .collect())
}
