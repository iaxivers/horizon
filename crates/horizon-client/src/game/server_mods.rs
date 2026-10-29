//! Server mods in the game (`docs/mods.md`, "Server mods"): the game that
//! simulates the world (in-frame single player, the server thread, a LAN
//! host, the dedicated server) runs them. At the end of each tick it tells
//! them who joined and left, then what players said, broke and placed since
//! the last one, then ticks them; a player's command reaches them at once.
//! Their lines go to the players they name. With no server mod on, each hook
//! here is one check.
use super::{Game, lan::LanHost};
#[cfg(not(feature = "mods"))]
use horizon_world::registry::BlockStateId;

/// Who did something a server mod hears about, resolved at the tick's end.
#[cfg_attr(not(feature = "mods"), allow(dead_code))]
pub(super) enum Who {
    /// The window's own player, in-frame.
    Local,
    /// A connection's player (the server thread's owner is one).
    Conn(u32),
    Uuid(u128),
}

/// What players did during a tick, for the server mods at its end.
#[cfg_attr(not(feature = "mods"), allow(dead_code))]
pub(super) enum ModEvent {
    Chat(Who, String),
    Block {
        who: Who,
        pos: [i32; 3],
        state: String,
        placed: bool,
    },
}

thread_local! {
    /// The player a server turn acts for now (`as_guest`), by UUID: whom a hook from inside
    /// the game's own code (the head of hurtServer) is about.
    static JAVA_ACTOR: std::cell::RefCell<Option<String>> = const { std::cell::RefCell::new(None) };
}

/// Sets the player acting now (None: no one); the one before, to put back.
pub(super) fn set_java_actor(actor: Option<String>) -> Option<String> {
    JAVA_ACTOR.with(|a| a.replace(actor))
}

/// The hook at the head of the player's hurtServer (`horizon_client::health::HURT_HOOK`): Java
/// mods' injections there and Fabric API's ALLOW_DAMAGE, for the player acting now.
#[cfg(feature = "java-mods")]
pub(super) fn java_hurt(damage_type: &str, amount: f32) -> horizon_client::health::HurtHook {
    use horizon_client::health::HurtHook;
    let Some(uuid) = JAVA_ACTOR.with(|a| a.borrow().clone()) else {
        return HurtHook::Damage(amount);
    };
    match horizon_jvm::events::hurt(&uuid, damage_type, amount) {
        Ok(Some(a)) => HurtHook::Damage(a),
        Ok(None) => HurtHook::Cancel,
        Err(e) => {
            log::warn!("Java mods: a hit failed: {e}");
            HurtHook::Damage(amount)
        }
    }
}

#[cfg(not(feature = "mods"))]
impl Game<'_> {
    pub(super) fn tick_server_mods(&mut self) {}
    pub(super) fn mod_block_event(&mut self, _pos: [i32; 3], _state: BlockStateId, _placed: bool) {}
    pub(super) fn mod_player_chat(&mut self, _who: Who, _text: &str) {}
    pub(super) fn local_server_mod_command(&mut self, _line: &str) -> bool {
        false
    }
    pub(super) fn guest_server_mod_command(
        &mut self,
        _host: &mut LanHost,
        _conn: u32,
        _line: &str,
    ) -> bool {
        false
    }
    pub(super) fn apply_mod_block_writes(&mut self) {}
    pub(super) fn java_before_break(&mut self, _pos: [i32; 3], _state: BlockStateId) -> bool {
        true
    }
    pub(super) fn note_java_death(&mut self) {}
    pub(super) fn note_java_death_cause(&mut self, _key: &str, _cause: Option<String>) {}
    pub(super) fn java_use_block(
        &mut self,
        _pos: [i32; 3],
        _face: i32,
        _point: [f64; 3],
        _off_hand: bool,
    ) -> Option<bool> {
        None
    }
    pub(super) fn java_menu_click(
        &mut self,
        _host: &mut LanHost,
        _conn: u32,
        _p: &horizon_protocol::packets::play::ContainerClick,
    ) -> bool {
        false
    }
    pub(super) fn java_menu_closed(
        &mut self,
        _host: &mut LanHost,
        _conn: u32,
        _window: i32,
    ) -> bool {
        false
    }
}

#[cfg(feature = "mods")]
mod run {
    use super::{Game, LanHost, ModEvent, Who};
    use horizon_client::chunk_map::ChunkMap;
    use horizon_client::{
        command::args::GameMode as Mode,
        lan::data::component_nbt,
        text::{self, Text},
    };
    use horizon_mods::{
        ArgumentKind, BlockPos, GameMode, Output, ServerCommand, ServerPlayer, WorldView,
    };
    use horizon_protocol::packets::play::{self, Clientbound as C};
    use horizon_world::registry::{BlockRegistry, BlockStateId};
    use serde_json::{Value, json};

    /// What Java mods hear of a tick (crate horizon-jvm).
    #[cfg(feature = "java-mods")]
    use horizon_jvm::events::Event as JavaEvent;
    #[cfg(not(feature = "java-mods"))]
    #[allow(dead_code)]
    enum JavaEvent {
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
        Block {
            uuid: String,
            position: [i32; 3],
            state: String,
            placed: bool,
        },
        Died {
            uuid: String,
            cause: String,
        },
        Respawned {
            uuid: String,
        },
    }

    /// Teleports Java mods asked for while the hub was out of `self.lan`, done next tick.
    #[cfg(feature = "java-mods")]
    static PENDING_TELEPORTS: std::sync::Mutex<Vec<horizon_jvm::events::Action>> =
        std::sync::Mutex::new(Vec::new());

    /// Whether Java mods run here: the bridge started Fabric with its platform mod.
    fn java_mods_on() -> bool {
        #[cfg(feature = "java-mods")]
        {
            horizon_jvm::events::enabled()
        }
        #[cfg(not(feature = "java-mods"))]
        {
            false
        }
    }

    /// The commands Java mods add (not vanilla's): their names, and their tree as a root of
    /// the commands report, merged into every player's command tree.
    struct JavaCommands {
        names: std::collections::HashSet<String>,
        tree: Value,
    }

    /// The Java mods' commands, once the bridge has loaded the server's data (they don't
    /// change after).
    fn java_commands() -> Option<&'static JavaCommands> {
        #[cfg(feature = "java-mods")]
        {
            static COMMANDS: std::sync::OnceLock<JavaCommands> = std::sync::OnceLock::new();
            if let Some(commands) = COMMANDS.get() {
                return Some(commands);
            }
            if !horizon_jvm::events::enabled() {
                return None;
            }
            let names: Vec<String> = horizon_jvm::events::command_names()
                .map_err(|e| log::warn!("Java mods: no commands: {e}"))
                .ok()?
                .into_iter()
                // Vanilla's, the dedicated server's and single player's own included: those
                // stay Horizon's.
                .filter(|n| {
                    !crate::game::mods::vanilla_command(n)
                        && !horizon_client::lan::commands::DEDICATED_ONLY.contains(&n.as_str())
                        && !horizon_client::lan::commands::INTEGRATED_ONLY.contains(&n.as_str())
                })
                .collect();
            let tree = horizon_jvm::events::command_tree(&names)
                .map_err(|e| log::warn!("Java mods: no command tree: {e}"))
                .ok()
                .and_then(|t| serde_json::from_str(&t).ok())?;
            if !names.is_empty() {
                log::info!("Java mods: commands /{}", names.join(", /"));
            }
            Some(COMMANDS.get_or_init(|| JavaCommands {
                names: names.into_iter().collect(),
                tree,
            }))
        }
        #[cfg(not(feature = "java-mods"))]
        {
            None
        }
    }

    /// Blocks queued for levels that don't tick (another dimension, in-frame)
    /// are kept up to this many; the oldest go first.
    const MAX_QUEUED_WRITES: usize = 1 << 16;

    /// Capability `world`'s view during a call: the levels' chunks, moved out
    /// of the game for the call and back after it (no copy).
    struct LentWorld {
        registry: &'static BlockRegistry,
        biomes: std::sync::Arc<horizon_client::biome::Biomes>,
        dims: Vec<(&'static str, ChunkMap)>,
    }
    impl WorldView for LentWorld {
        fn block(&self, dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
            let (_, chunks) = self.dims.iter().find(|(d, _)| *d == dimension)?;
            let chunk = chunks.get(&(x >> 4, z >> 4))?;
            Some(state_string(self.registry, chunk.block(x & 15, y, z & 15)))
        }
        fn biome(&self, dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
            let (_, chunks) = self.dims.iter().find(|(d, _)| *d == dimension)?;
            let chunk = chunks.get(&(x >> 4, z >> 4))?;
            Some(self.biomes.get(chunk.biome(x, y, z)).name.clone())
        }
        fn has_dimension(&self, dimension: &str) -> bool {
            matches!(
                dimension,
                "minecraft:overworld" | "minecraft:the_nether" | "minecraft:the_end"
            )
        }
        fn check_state(&self, state: &str) -> Result<(), String> {
            crate::game::render_control::parse_state(self.registry, state)
                .map(drop)
                .map_err(|e| format!("{state}: {e}"))
        }
    }

    /// Horizon's levels as Java mods read them during a call (crate horizon-jvm's `world`):
    /// borrowed, not lent, so nothing moves.
    #[cfg(feature = "java-mods")]
    struct JavaBlocks<'a> {
        registry: &'static BlockRegistry,
        dims: Vec<(&'static str, &'a ChunkMap)>,
    }
    #[cfg(feature = "java-mods")]
    impl horizon_jvm::world::Blocks for JavaBlocks<'_> {
        fn block(&self, dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
            let (_, chunks) = self.dims.iter().find(|(d, _)| *d == dimension)?;
            let chunk = chunks.get(&(x >> 4, z >> 4))?;
            Some(state_string(self.registry, chunk.block(x & 15, y, z & 15)))
        }
    }

    /// A guest's item in vanilla's `Inventory` slot `slot`: 0-35 the inventory, 36 feet to
    /// 39 head (Horizon keeps the armour head first), 40 the off hand.
    #[cfg(feature = "java-mods")]
    fn guest_slot(
        g: &mut horizon_client::lan::guest::Guest,
        slot: usize,
    ) -> Option<&mut Option<horizon_client::items::ItemStack>> {
        match slot {
            0..=35 => g.inventory.get_mut(slot),
            36..=39 => g.armor.get_mut(39 - slot),
            40 => Some(&mut g.offhand),
            _ => None,
        }
    }

    /// The hash of each player's inventory as Java mods last had it.
    #[cfg(feature = "java-mods")]
    static JAVA_INVENTORIES: std::sync::Mutex<Option<std::collections::HashMap<u128, u64>>> =
        std::sync::Mutex::new(None);

    /// A guest's inventory for Java mods (vanilla's order, SNBT per slot), when it changed
    /// since they last had it.
    #[cfg(feature = "java-mods")]
    fn java_inventory(g: &horizon_client::lan::guest::Guest) -> Option<Vec<String>> {
        use std::hash::{Hash, Hasher};
        let slots: Vec<String> = (0..horizon_jvm::events::INVENTORY_SLOTS)
            .map(|v| {
                let stack = match v {
                    0..=35 => g.inventory.get(v).cloned().flatten(),
                    36..=39 => g.armor[39 - v].clone(),
                    _ => g.offhand.clone(),
                };
                stack.map_or_else(String::new, |stack| {
                    horizon_client::snbt::to_snbt(&horizon_items::item_nbt::item_to_nbt(&stack))
                })
            })
            .collect();
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        slots.hash(&mut hasher);
        let hash = hasher.finish();
        let mut seen = JAVA_INVENTORIES.lock().unwrap_or_else(|e| e.into_inner());
        let seen = seen.get_or_insert_with(Default::default);
        (seen.insert(g.uuid, hash) != Some(hash)).then_some(slots)
    }

    /// Whether each player was dead at the last tick Java mods heard of (for deaths and
    /// respawns).
    #[cfg(feature = "java-mods")]
    static JAVA_DEAD: std::sync::Mutex<Option<std::collections::HashMap<String, bool>>> =
        std::sync::Mutex::new(None);

    /// What killed each player who died since the last tick (noted at its death message).
    #[cfg(feature = "java-mods")]
    static JAVA_DEATH_CAUSES: std::sync::Mutex<Option<std::collections::HashMap<String, String>>> =
        std::sync::Mutex::new(None);

    /// Items Java mods dropped, by dimension, made when their level is in place.
    #[cfg(feature = "java-mods")]
    /// The windows of Java mods' menus, by player: (connection, window).
    #[cfg(feature = "java-mods")]
    static JAVA_WINDOWS: std::sync::Mutex<std::collections::BTreeMap<String, (u32, i32)>> =
        std::sync::Mutex::new(std::collections::BTreeMap::new());

    /// The player whose Java mod's menu has `window` open on `conn`.
    #[cfg(feature = "java-mods")]
    fn java_window_of(host: &LanHost, conn: u32, window: i32) -> Option<String> {
        let uuid = host
            .hub
            .guests
            .iter()
            .find(|g| g.conn == conn)
            .map(|g| uuid_string(g.uuid))?;
        let windows = JAVA_WINDOWS.lock().unwrap_or_else(|e| e.into_inner());
        (windows.get(&uuid) == Some(&(conn, window))).then_some(uuid)
    }

    static JAVA_DROPS: std::sync::Mutex<Vec<(String, horizon_jvm::events::Action)>> =
        std::sync::Mutex::new(Vec::new());

    /// Server mods' commands as a root of the commands report
    /// (`lan::commands`): a literal per command, then its arguments in a
    /// chain, each node runnable where only optional arguments follow.
    fn command_tree<'a>(commands: impl Iterator<Item = &'a ServerCommand>) -> Value {
        let mut root = serde_json::Map::new();
        for c in commands {
            let mut next: Option<(String, Value)> = None;
            for (i, a) in c.arguments.iter().enumerate().rev() {
                let executable = c.arguments.get(i + 1).is_none_or(|b| b.optional);
                let (parser, properties) = match a.kind {
                    ArgumentKind::Word => ("brigadier:string", json!({"type": "word"})),
                    ArgumentKind::Text => ("brigadier:string", json!({"type": "greedy"})),
                    ArgumentKind::Integer((min, max)) => {
                        let mut p = serde_json::Map::new();
                        if let Some(min) = min {
                            p.insert("min".into(), min.into());
                        }
                        if let Some(max) = max {
                            p.insert("max".into(), max.into());
                        }
                        ("brigadier:integer", Value::Object(p))
                    }
                    ArgumentKind::Number => ("brigadier:double", Value::Null),
                    ArgumentKind::Boolean => ("brigadier:bool", Value::Null),
                    ArgumentKind::Player => (
                        "minecraft:entity",
                        json!({"amount": "single", "type": "players"}),
                    ),
                    ArgumentKind::BlockPos => ("minecraft:block_pos", Value::Null),
                };
                let mut node = json!({
                    "type": "argument",
                    "parser": parser,
                    "properties": properties,
                    "executable": executable,
                });
                if let Some((name, child)) = next.take() {
                    node["children"] = json!({ name: child });
                }
                next = Some((a.name.clone(), node));
            }
            let executable = c.arguments.first().is_none_or(|a| a.optional);
            let mut literal = json!({"type": "literal", "executable": executable});
            if let Some((name, child)) = next {
                literal["children"] = json!({ name: child });
            }
            root.insert(c.name.clone(), literal);
        }
        if root.is_empty() {
            return Value::Null;
        }
        json!({"type": "root", "children": root})
    }

    /// `minecraft:oak_log[axis=y]`.
    fn state_string(registry: &BlockRegistry, state: BlockStateId) -> String {
        let Some(s) = registry.state(state) else {
            return "minecraft:air".into();
        };
        if s.properties.is_empty() {
            return s.name.clone();
        }
        let props: Vec<String> = s
            .properties
            .iter()
            .map(|(k, v)| format!("{k}={v}"))
            .collect();
        format!("{}[{}]", s.name, props.join(","))
    }

    /// `UUID.toString()`.
    fn uuid_string(u: u128) -> String {
        let h = format!("{u:032x}");
        format!(
            "{}-{}-{}-{}-{}",
            &h[..8],
            &h[8..12],
            &h[12..16],
            &h[16..20],
            &h[20..]
        )
    }

    fn mode(id: i32) -> GameMode {
        match id {
            1 => GameMode::Creative,
            2 => GameMode::Adventure,
            3 => GameMode::Spectator,
            _ => GameMode::Survival,
        }
    }

    impl Game<'_> {
        /// The players on this game's server: the window's own player
        /// in-frame, then every connection's. `host` is the hub when it is
        /// out of `self.lan`.
        pub(in crate::game) fn server_players(&self, host: Option<&LanHost>) -> Vec<ServerPlayer> {
            let mut out = Vec::new();
            if self.options.execution_role.is_client()
                && self.remote.is_none()
                && let Some(p) = &self.player
            {
                out.push(ServerPlayer {
                    id: self.player_uuid_string(),
                    name: crate::game::chat::PLAYER_NAME.to_owned(),
                    x: p.position.x,
                    y: p.position.y,
                    z: p.position.z,
                    dimension: self.dim.id().to_owned(),
                    game_mode: match self.mode {
                        Mode::Survival => GameMode::Survival,
                        Mode::Creative => GameMode::Creative,
                        Mode::Adventure => GameMode::Adventure,
                        Mode::Spectator => GameMode::Spectator,
                    },
                    health: p.health.health(),
                });
            }
            let guests = host
                .or(self.lan.as_ref())
                .into_iter()
                .flat_map(|h| &h.hub.guests);
            out.extend(guests.filter(|g| g.placed).map(|g| ServerPlayer {
                id: uuid_string(g.uuid),
                name: g.name.clone(),
                x: g.position[0],
                y: g.position[1],
                z: g.position[2],
                dimension: g.dimension.clone(),
                game_mode: mode(g.game_mode),
                health: g.health,
            }));
            out
        }

        /// Whether what players do now reaches server mods: one is running
        /// and this game simulates the world.
        fn hears_players(&self) -> bool {
            (self.mods.server || java_mods_on()) && self.remote.is_none()
        }

        /// The tick's end: joins and leaves, the tick's events, the tick hook.
        pub(in crate::game) fn tick_server_mods(&mut self) {
            if !self.options.execution_role.is_client() {
                // A server has no frames to check its dev mods in.
                self.reload_dev_mods();
            }
            self.send_server_mod_commands();
            let wasm = self.mods.server;
            let java = java_mods_on() && self.remote.is_none();
            if !wasm && !java {
                return;
            }
            let players = self.server_players(None);
            if wasm {
                let snapshot = self.mod_snapshot_with(players.clone());
                self.mods.mods.set_snapshot(snapshot);
                self.lend_world();
            }
            let before = std::mem::take(&mut self.mods.players);
            // Java mods hear the same joins, leaves and actions, in the same order.
            let mut java_events = Vec::new();
            for p in before
                .iter()
                .filter(|p| !players.iter().any(|q| q.id == p.id))
            {
                if wasm {
                    self.mods.mods.player_left(p);
                }
                if java {
                    java_events.push(JavaEvent::Left { uuid: p.id.clone() });
                }
            }
            for p in players
                .iter()
                .filter(|p| !before.iter().any(|q| q.id == p.id))
            {
                if wasm {
                    self.mods.mods.player_joined(p);
                }
                if java {
                    java_events.push(JavaEvent::Joined { uuid: p.id.clone() });
                }
            }
            for event in std::mem::take(&mut self.mods.events) {
                let who = |w: &Who| {
                    let id = match w {
                        Who::Local => self.player_uuid_string(),
                        Who::Uuid(u) => uuid_string(*u),
                        Who::Conn(c) => {
                            let guests = self.lan.iter().flat_map(|h| &h.hub.guests);
                            let g = guests.into_iter().find(|g| g.conn == *c);
                            g.map_or_else(String::new, |g| uuid_string(g.uuid))
                        }
                    };
                    players.iter().chain(&before).find(|p| p.id == id).cloned()
                };
                match event {
                    ModEvent::Chat(w, text) => {
                        let Some(p) = who(&w) else { continue };
                        if java {
                            java_events.push(JavaEvent::Chat {
                                uuid: p.id.clone(),
                                text: text.clone(),
                            });
                        }
                        if wasm {
                            self.mods.mods.player_chat(&p, &text);
                        }
                    }
                    ModEvent::Block {
                        who: w,
                        pos,
                        state,
                        placed,
                    } => {
                        let Some(p) = who(&w) else { continue };
                        if java {
                            java_events.push(JavaEvent::Block {
                                uuid: p.id.clone(),
                                position: pos,
                                state: state.clone(),
                                placed,
                            });
                        }
                        if !wasm {
                            continue;
                        }
                        let pos = BlockPos {
                            x: pos[0],
                            y: pos[1],
                            z: pos[2],
                        };
                        if placed {
                            self.mods.mods.block_placed(&p, pos, &state);
                        } else {
                            self.mods.mods.block_broken(&p, pos, &state);
                        }
                    }
                }
            }
            if wasm {
                self.mods.mods.server_tick(self.ticks);
                self.return_world();
            }
            if java {
                self.tick_java_mods(&players, &java_events);
            }
            self.mods.players = players;
            if wasm {
                self.show_mod_output();
                self.apply_mod_block_writes();
            }
        }

        /// Hands the tick to the Java mods (crate horizon-jvm: Fabric API's events), then sends
        /// what they asked: system messages to a player or everyone.
        #[cfg(feature = "java-mods")]
        fn tick_java_mods(&mut self, players: &[ServerPlayer], events: &[JavaEvent]) {
            // Teleports Java mods asked for while the hub was out of `self.lan`.
            let pending =
                std::mem::take(&mut *PENDING_TELEPORTS.lock().unwrap_or_else(|e| e.into_inner()));
            for action in pending {
                self.java_teleport(action);
            }
            // A connection's facing, or the window's own player's in-frame.
            let rotation = |id: &str| -> [f32; 2] {
                self.lan
                    .iter()
                    .flat_map(|h| &h.hub.guests)
                    .find(|g| uuid_string(g.uuid) == id)
                    .map_or([self.yaw, self.pitch], |g| [g.yaw, g.pitch])
            };
            // A connection's food, or the window's own player's in-frame.
            let food = |id: &str| -> (i32, f32) {
                self.lan
                    .iter()
                    .flat_map(|h| &h.hub.guests)
                    .find(|g| uuid_string(g.uuid) == id)
                    .map(|g| (g.food, g.saturation))
                    .or_else(|| {
                        self.player
                            .as_ref()
                            .map(|p| (p.health.food.food, p.health.food.saturation))
                    })
                    .unwrap_or((20, 5.0))
            };
            let players: Vec<horizon_jvm::events::Player> = players
                .iter()
                .map(|p| horizon_jvm::events::Player {
                    uuid: p.id.clone(),
                    name: p.name.clone(),
                    position: [p.x, p.y, p.z],
                    yaw: rotation(&p.id)[0],
                    pitch: rotation(&p.id)[1],
                    dimension: p.dimension.clone(),
                    game_mode: match p.game_mode {
                        GameMode::Survival => 0,
                        GameMode::Creative => 1,
                        GameMode::Adventure => 2,
                        GameMode::Spectator => 3,
                    },
                    health: p.health,
                    food: food(&p.id).0,
                    saturation: food(&p.id).1,
                    // A connection's level (ops, the LAN host's grant); the window's own player
                    // in-frame owns its world.
                    permission: self
                        .lan
                        .iter()
                        .flat_map(|h| &h.hub.guests)
                        .find(|g| uuid_string(g.uuid) == p.id)
                        .map_or(4, |g| g.permission),
                    inventory: self
                        .lan
                        .iter()
                        .flat_map(|h| &h.hub.guests)
                        .find(|g| uuid_string(g.uuid) == p.id)
                        .and_then(java_inventory),
                })
                .collect();
            // Not `options.dedicated`: single player's server thread sets it too.
            let dedicated = matches!(
                self.options.execution_role,
                crate::game::execution_role::ExecutionRole::DedicatedServer
            );
            // Deaths and respawns, from each player's health since the last tick.
            let mut events = events.to_vec();
            {
                let mut dead = JAVA_DEAD.lock().unwrap_or_else(|e| e.into_inner());
                let dead = dead.get_or_insert_with(Default::default);
                for p in &players {
                    // A connection's player is dead once the server's ServerPlayer.die ran for it
                    // (guest_die: its message and drops made); the window's own at no health.
                    let now = self
                        .lan
                        .iter()
                        .flat_map(|h| &h.hub.guests)
                        .find(|g| uuid_string(g.uuid) == p.uuid)
                        .map_or(p.health <= 0.0, |g| g.dead);
                    let was = dead.insert(p.uuid.clone(), now).unwrap_or(false);
                    if now && !was {
                        let conn = self
                            .lan
                            .iter()
                            .flat_map(|h| &h.hub.guests)
                            .find(|g| uuid_string(g.uuid) == p.uuid)
                            .map(|g| g.conn);
                        let tracker = match conn {
                            Some(conn) => self.guest_combat.get(&conn),
                            None => Some(&self.combat),
                        };
                        // Noted where the death message is made, else the tracker's last hit.
                        let noted = JAVA_DEATH_CAUSES
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .as_mut()
                            .and_then(|m| {
                                let by_conn = conn.and_then(|c| m.remove(&format!("conn:{c}")));
                                m.remove(&p.uuid).or(by_conn)
                            });
                        let cause = noted.unwrap_or_else(|| {
                            tracker
                                .and_then(|t| t.last_damage_type())
                                .unwrap_or("minecraft:generic")
                                .to_string()
                        });
                        events.push(JavaEvent::Died {
                            uuid: p.uuid.clone(),
                            cause,
                        });
                    } else if was && !now {
                        events.push(JavaEvent::Respawned {
                            uuid: p.uuid.clone(),
                        });
                    }
                }
            }
            let (ticks, root) = (self.ticks as i64, self.save.root.clone());
            let ticked = self.with_java_world(|| {
                horizon_jvm::events::server_tick(ticks, dedicated, &root, &players, &events)
            });
            if let Err(e) = ticked {
                log::warn!("Java mods: a tick failed: {e}");
            }
            self.apply_java_actions(None);
        }

        /// Sends what the Java mods asked since the last call: system messages to a player or
        /// everyone. `host` is the hub when it is out of `self.lan`.
        #[cfg(feature = "java-mods")]
        fn apply_java_actions(&mut self, mut host: Option<&mut LanHost>) {
            let mut wrote = false;
            for action in horizon_jvm::events::take_actions() {
                wrote |= matches!(
                    action,
                    horizon_jvm::events::Action::SetBlock { .. }
                        | horizon_jvm::events::Action::DropItem { .. }
                );
                match action {
                    horizon_jvm::events::Action::Tell { uuid, text } => {
                        self.tell_server_player(host.as_deref_mut(), &uuid, Text::literal(text));
                    }
                    teleport @ horizon_jvm::events::Action::Teleport { .. } => {
                        if host.is_some() {
                            // The hub is out of `self.lan` (a connection's command): the
                            // player's /tp needs it, so the next tick does it.
                            PENDING_TELEPORTS
                                .lock()
                                .unwrap_or_else(|e| e.into_inner())
                                .push(teleport);
                        } else {
                            self.java_teleport(teleport);
                        }
                    }
                    horizon_jvm::events::Action::Abilities {
                        uuid,
                        game_mode,
                        flags,
                        flying_speed,
                        walking_speed,
                    } => {
                        let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                            continue;
                        };
                        let to = host
                            .hub
                            .guests
                            .iter()
                            .find(|g| uuid_string(g.uuid) == uuid)
                            .map(|g| g.conn);
                        if let Some(conn) = to {
                            host.hub.set_mod_abilities(
                                conn,
                                horizon_client::lan::guest::ModAbilities {
                                    game_mode,
                                    flags,
                                    flying_speed,
                                    walking_speed,
                                },
                            );
                        }
                    }
                    horizon_jvm::events::Action::TabName { uuid, json } => {
                        let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                            continue;
                        };
                        let Some(id) = host
                            .hub
                            .guests
                            .iter()
                            .find(|g| uuid_string(g.uuid) == uuid)
                            .map(|g| g.uuid)
                        else {
                            continue;
                        };
                        let name = json
                            .and_then(|j| serde_json::from_str::<serde_json::Value>(&j).ok())
                            .map(|v| {
                                Self::uuid_int_arrays(horizon_client::lan::data::component_nbt(&v))
                            });
                        host.hub.set_tab_name(id, name);
                    }
                    horizon_jvm::events::Action::OpenMenu { uuid, menu, title } => {
                        if let Some(h) = host.as_deref_mut() {
                            self.java_open_menu(h, &uuid, &menu, title.as_deref());
                        } else if let Some(mut lan) = self.lan.take() {
                            self.java_open_menu(&mut lan, &uuid, &menu, title.as_deref());
                            self.lan = Some(lan);
                        }
                    }
                    horizon_jvm::events::Action::Food {
                        uuid,
                        food,
                        saturation,
                    } => {
                        let guest = host.as_deref_mut().or(self.lan.as_mut()).and_then(|h| {
                            h.hub
                                .guests
                                .iter_mut()
                                .find(|g| uuid_string(g.uuid) == uuid)
                        });
                        if let Some(g) = guest {
                            g.food = food.clamp(0, 20);
                            g.saturation = saturation.max(0.0);
                        } else if uuid == self.player_uuid_string()
                            && let Some(p) = self.player.as_mut()
                        {
                            p.health.food.food = food.clamp(0, 20);
                            p.health.food.saturation = saturation.max(0.0);
                        }
                    }
                    horizon_jvm::events::Action::MenuContent {
                        uuid,
                        open,
                        items,
                        carried,
                    } => {
                        let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                            continue;
                        };
                        let Some(conn) = host
                            .hub
                            .guests
                            .iter()
                            .find(|g| uuid_string(g.uuid) == uuid)
                            .map(|g| g.conn)
                        else {
                            continue;
                        };
                        let mut windows = JAVA_WINDOWS.lock().unwrap_or_else(|e| e.into_inner());
                        let Some(items) = items else {
                            if let Some((c, w)) = windows.remove(&uuid) {
                                host.hub.close_window(c, w);
                            }
                            continue;
                        };
                        if let Some((menu, title)) = open {
                            let title = title
                                .and_then(|j| serde_json::from_str::<Value>(&j).ok())
                                .unwrap_or(Value::Null);
                            let title = horizon_client::lan::data::component_nbt(&title);
                            match host.hub.open_window(conn, &menu, title) {
                                Some(w) => {
                                    windows.insert(uuid.clone(), (conn, w));
                                }
                                None => {
                                    log::warn!("Java mods: a menu of type {menu} can't be shown")
                                }
                            }
                        }
                        let Some(&(c, w)) = windows.get(&uuid) else {
                            continue;
                        };
                        let item = |s: &Option<String>| {
                            let snbt = s.as_deref()?;
                            match horizon_world::nbt::parse_snbt(snbt) {
                                Ok((tag, _)) => horizon_items::item_nbt::item_from_nbt(&tag),
                                Err(e) => {
                                    log::warn!("Java mods: a mod's item {snbt}: {e}");
                                    None
                                }
                            }
                        };
                        let stacks = items.iter().map(item).collect();
                        let carried = item(&carried);
                        host.hub.send_window_content(c, w, stacks, carried);
                    }
                    horizon_jvm::events::Action::SetBlock {
                        dimension,
                        position: [x, y, z],
                        state,
                    } => {
                        let queued = &mut self.mods.block_writes;
                        queued.push((dimension, horizon_mods::BlockChange { x, y, z, state }));
                        if queued.len() > MAX_QUEUED_WRITES {
                            let over = queued.len() - MAX_QUEUED_WRITES;
                            queued.drain(..over);
                            log::warn!(
                                "Java mods: more blocks set than wait for their level: {over} dropped"
                            );
                        }
                    }
                    drop @ (horizon_jvm::events::Action::DropItem { .. }
                    | horizon_jvm::events::Action::LevelEvent { .. }) => {
                        let (horizon_jvm::events::Action::DropItem { dimension, .. }
                        | horizon_jvm::events::Action::LevelEvent { dimension, .. }) = &drop
                        else {
                            continue;
                        };
                        let mut drops = JAVA_DROPS.lock().unwrap_or_else(|e| e.into_inner());
                        if drops.len() < 4096 {
                            drops.push((dimension.clone(), drop));
                        }
                    }
                    horizon_jvm::events::Action::SetSlot { uuid, slot, item } => {
                        let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                            continue;
                        };
                        let stack = match &item {
                            Some(snbt) => match horizon_world::nbt::parse_snbt(snbt) {
                                Ok((tag, _)) => horizon_items::item_nbt::item_from_nbt(&tag),
                                Err(e) => {
                                    log::warn!("Java mods: a mod's item {snbt}: {e}");
                                    continue;
                                }
                            },
                            None => None,
                        };
                        if let Some(g) = host
                            .hub
                            .guests
                            .iter_mut()
                            .find(|g| uuid_string(g.uuid) == uuid)
                            && let Some(at) = guest_slot(g, slot)
                        {
                            // The hub sends the changed slot with the tick's other changes.
                            *at = stack;
                        }
                    }
                    horizon_jvm::events::Action::Broadcast { text } => {
                        if self.options.dedicated.is_some() {
                            super::super::dedicated::log("INFO", &text);
                        } else {
                            log::info!("{text}");
                        }
                        for p in self.server_players(host.as_deref()) {
                            self.tell_server_player(
                                host.as_deref_mut(),
                                &p.id,
                                Text::literal(text.clone()),
                            );
                        }
                    }
                }
            }
            // The level in place gets the mods' blocks and drops now; the others' wait.
            if wrote {
                self.apply_mod_block_writes();
            }
        }

        /// Opens Horizon's own menu for one a Java mod opened (`ServerPlayer.openMenu`):
        /// a crafting grid, the player's ender chest, a stonecutter, grindstone, loom,
        /// cartography or smithing table, with no block behind it (`/workbench`). Other
        /// menus are the mod's own; Horizon can't show those yet.
        #[cfg(feature = "java-mods")]
        fn java_open_menu(
            &mut self,
            host: &mut LanHost,
            uuid: &str,
            menu: &str,
            title: Option<&str>,
        ) {
            use super::super::stations::{Kind, Station};
            let Some(g) = host.hub.guests.iter().find(|g| uuid_string(g.uuid) == uuid) else {
                return;
            };
            let conn = g.conn;
            let at = g.position.map(|v| v.floor() as i32);
            let ender_items = g.ender_items.clone();
            let title_json = title
                .and_then(|j| serde_json::from_str::<serde_json::Value>(j).ok())
                .unwrap_or(serde_json::Value::Null);
            let station = match menu {
                "minecraft:stonecutter" => Some(Kind::Stonecutter),
                "minecraft:grindstone" => Some(Kind::Grindstone),
                "minecraft:loom" => Some(Kind::Loom),
                "minecraft:cartography_table" => Some(Kind::Cartography),
                "minecraft:smithing" => Some(Kind::Smithing),
                _ => None,
            };
            if menu == "minecraft:crafting" {
                self.lan_open_crafting(host, conn, at);
            } else if let Some(kind) = station {
                self.lan_open_station(host, conn, &Station::new(kind, at));
            } else if menu == "minecraft:generic_9x3"
                && title_json.to_string().contains("container.enderchest")
            {
                // PlayerEnderChestContainer: the guest's own ender chest items.
                self.guest_container_closed(host, conn);
                self.lan_station_closed(host, conn, None);
                self.lan_crafting_closed(host, conn, None);
                host.hub.open_unplaced_container(
                    conn,
                    menu,
                    horizon_client::lan::data::component_nbt(&title_json),
                    horizon_client::lan::menu::Slots::Ender,
                    ender_items,
                );
            } else {
                static TOLD: std::sync::OnceLock<
                    std::sync::Mutex<std::collections::HashSet<String>>,
                > = std::sync::OnceLock::new();
                let told = TOLD.get_or_init(Default::default);
                if told
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .insert(menu.to_string())
                {
                    log::info!(
                        "Java mods: a mod opened a {menu} menu; Horizon shows only vanilla's own menus yet"
                    );
                }
                return;
            }
            let window = host.hub.guest(conn).map(|g| g.next_window);
            host.play.entry(conn).or_default().mod_window = window;
        }

        /// UUIDs in a component (a hover's `show_entity`) as vanilla's NBT has them: int arrays
        /// (UUIDUtil.CODEC), not the lists of ints JSON gives.
        #[cfg(feature = "java-mods")]
        fn uuid_int_arrays(tag: horizon_world::nbt::Tag) -> horizon_world::nbt::Tag {
            use horizon_world::nbt::Tag;
            match tag {
                Tag::Compound(entries) => Tag::Compound(
                    entries
                        .into_iter()
                        .map(|(k, v)| {
                            let v = match v {
                                Tag::List(items)
                                    if k.to_string().is_ok_and(|k| k == "uuid")
                                        && items.len() == 4 =>
                                {
                                    let ints: Option<Vec<i32>> = items
                                        .iter()
                                        .map(|t| match t {
                                            Tag::Int(i) => Some(*i),
                                            _ => None,
                                        })
                                        .collect();
                                    ints.map_or(Tag::List(items), Tag::IntArray)
                                }
                                other => Self::uuid_int_arrays(other),
                            };
                            (k, v)
                        })
                        .collect(),
                ),
                Tag::List(items) => {
                    Tag::List(items.into_iter().map(Self::uuid_int_arrays).collect())
                }
                other => other,
            }
        }

        /// Moves a player as a Java mod asked: Horizon's own `/tp`, run by the server in the
        /// target dimension (`execute in`), so a connection, the window's player and other
        /// dimensions all go the way vanilla's teleports go.
        #[cfg(feature = "java-mods")]
        fn java_teleport(&mut self, action: horizon_jvm::events::Action) {
            let horizon_jvm::events::Action::Teleport {
                uuid,
                dimension,
                position: [x, y, z],
                yaw,
                pitch,
            } = action
            else {
                return;
            };
            let Some(name) = self
                .server_players(None)
                .into_iter()
                .find(|p| p.id == uuid)
                .map(|p| p.name)
            else {
                return;
            };
            let line = format!("execute in {dimension} run tp {name} {x} {y} {z} {yaw} {pitch}");
            // The server's own command (a function's rights), not the player's.
            let permission = std::mem::replace(&mut self.ui.registries.permission, 4);
            let source = self.server_source();
            self.exec = Default::default();
            let result = self.execute_line(&line, source);
            self.ui.registries.permission = permission;
            if let Err(e) = result {
                log::warn!(
                    "Java mods: a teleport failed ({line}): {}",
                    e.message.plain()
                );
            }
        }

        #[cfg(not(feature = "java-mods"))]
        fn tick_java_mods(&mut self, _players: &[ServerPlayer], _events: &[JavaEvent]) {}

        /// Players' command trees list the running server mods' commands
        /// after vanilla's, whenever they change (or a hub opens).
        fn send_server_mod_commands(&mut self) {
            let java = java_commands();
            // Java mods' commands load once, a while after the world opens.
            let key = self.mods.mods.generation() ^ if java.is_some() { 1 << 63 } else { 0 };
            let Some(host) = self.lan.as_mut() else {
                return;
            };
            if host.hub.mod_commands_key() == Some(key) {
                return;
            }
            let mut tree = command_tree(self.mods.mods.server_commands());
            if let Some(java) = java {
                if tree.is_null() {
                    tree = java.tree.clone();
                } else if let (Some(ours), Some(theirs)) = (
                    tree["children"].as_object_mut(),
                    java.tree["children"].as_object(),
                ) {
                    // A WASM server mod's command of the same name wins, as it runs first.
                    for (name, node) in theirs {
                        ours.entry(name.clone()).or_insert_with(|| node.clone());
                    }
                }
            }
            host.hub.set_mod_commands(key, tree);
        }

        /// `/line` from the player with this id, when a Java mod owns the command: run in the
        /// JVM as that player, its answers sent back. False when no Java mod owns it.
        fn java_command(&mut self, host: Option<&mut LanHost>, uuid: &str, line: &str) -> bool {
            if !java_mods_on() {
                return false;
            }
            let name = line.split_whitespace().next().unwrap_or_default();
            let Some(java) = java_commands() else {
                return false;
            };
            if !java.names.contains(name) {
                return false;
            }
            #[cfg(feature = "java-mods")]
            {
                match self.with_java_world(|| horizon_jvm::events::run_command(uuid, line)) {
                    Ok(true) => {}
                    Ok(false) => return false,
                    Err(e) => {
                        log::warn!("Java mods: /{line} failed: {e}");
                        return false;
                    }
                }
                self.apply_java_actions(host);
                true
            }
            #[cfg(not(feature = "java-mods"))]
            {
                let _ = (host, uuid);
                false
            }
        }

        /// Runs `f`, a call into Java, with the levels as what Java mods read of the world.
        #[cfg(feature = "java-mods")]
        fn with_java_world<R>(&mut self, f: impl FnOnce() -> R) -> R {
            let registry = self.pack.assets.registry;
            let mut dims: Vec<(&'static str, &ChunkMap)> =
                vec![(self.dim.id(), &self.world.chunks)];
            for (dim, chunks) in self.levels.parked_chunks() {
                dims.push((dim.id(), &*chunks));
            }
            horizon_jvm::world::with_blocks(&JavaBlocks { registry, dims }, f)
        }

        /// The slot changes Java mods made to the player acting now, made at once in the
        /// inventory in place (a guest's is swapped into the game for its turn, and put back
        /// after it): what the game then does to it (an axe's wear for the break) adds to them.
        #[cfg(feature = "java-mods")]
        fn apply_java_actor_slots(&mut self, uuid: &str) {
            let mut later = Vec::new();
            for action in horizon_jvm::events::take_actions() {
                if let horizon_jvm::events::Action::Food {
                    uuid: of,
                    food,
                    saturation,
                } = &action
                    && of == uuid
                    && let Some(p) = self.player.as_mut()
                {
                    // The acting player's food is in `self.player` for the call.
                    p.health.food.food = (*food).clamp(0, 20);
                    p.health.food.saturation = saturation.max(0.0);
                    continue;
                }
                let horizon_jvm::events::Action::SetSlot {
                    uuid: of,
                    slot,
                    item,
                } = &action
                else {
                    later.push(action);
                    continue;
                };
                if of != uuid {
                    later.push(action);
                    continue;
                }
                let stack = match item {
                    Some(snbt) => match horizon_world::nbt::parse_snbt(snbt) {
                        Ok((tag, _)) => horizon_items::item_nbt::item_from_nbt(&tag),
                        Err(_) => continue,
                    },
                    None => None,
                };
                let at = match *slot {
                    0..=35 => self.inventory.get_mut(*slot),
                    36..=39 => self.armor.get_mut(39 - *slot),
                    40 => Some(&mut self.offhand),
                    _ => None,
                };
                if let Some(at) = at {
                    *at = stack;
                }
            }
            for action in later {
                horizon_jvm::events::push_action(action);
            }
        }

        /// The player acting now is dying: what killed it (its tracker's last hit), for the
        /// Java mods' death hooks at the next tick.
        pub(in crate::game) fn note_java_death(&mut self) {
            #[cfg(feature = "java-mods")]
            {
                if !java_mods_on() {
                    return;
                }
                let uuid = self
                    .lan_acting_uuid
                    .map(uuid_string)
                    .unwrap_or_else(|| self.player_uuid_string());
                self.note_java_death_of(&uuid);
            }
        }

        /// What killed the player named by `key` (its UUID, or `conn:N`), when known.
        pub(in crate::game) fn note_java_death_cause(&mut self, key: &str, cause: Option<String>) {
            #[cfg(feature = "java-mods")]
            if java_mods_on()
                && let Some(cause) = cause
            {
                JAVA_DEATH_CAUSES
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .get_or_insert_with(Default::default)
                    .insert(key.to_string(), cause);
            }
            #[cfg(not(feature = "java-mods"))]
            let _ = (key, cause);
        }

        /// [`Game::note_java_death`] for a player named by `key` (its UUID, or `conn:N`).
        pub(in crate::game) fn note_java_death_of(&mut self, key: &str) {
            #[cfg(feature = "java-mods")]
            {
                if !java_mods_on() {
                    return;
                }
                let uuid = key.to_string();
                if let Some(cause) = self.combat.last_damage_type() {
                    JAVA_DEATH_CAUSES
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .get_or_insert_with(Default::default)
                        .insert(uuid, cause.to_string());
                }
            }
        }

        /// The player acting now uses (right-clicks) the block `pos` on `face` (vanilla's
        /// `Direction` id) at `point`: vanilla's `useItemOn` head for Java mods (Fabric API's
        /// `UseBlockCallback`, library events). Some(swing) when a mod took the click, so the
        /// game does nothing more.
        pub(in crate::game) fn java_use_block(
            &mut self,
            pos: [i32; 3],
            face: i32,
            point: [f64; 3],
            off_hand: bool,
        ) -> Option<bool> {
            #[cfg(feature = "java-mods")]
            {
                if !java_mods_on() || !self.hears_players() {
                    return None;
                }
                let uuid = self
                    .lan_acting_uuid
                    .map(uuid_string)
                    .unwrap_or_else(|| self.player_uuid_string());
                let (dim, selected) = (self.dim.id(), self.selected as i32);
                let used = self.with_java_world(|| {
                    horizon_jvm::events::use_block(
                        &uuid,
                        dim,
                        pos,
                        face,
                        point,
                        i32::from(off_hand),
                        selected,
                    )
                });
                self.apply_java_actor_slots(&uuid);
                match used {
                    Ok(horizon_jvm::events::UseResult::Pass) => None,
                    Ok(horizon_jvm::events::UseResult::Taken) => Some(false),
                    Ok(horizon_jvm::events::UseResult::TakenSwing) => Some(true),
                    Err(e) => {
                        log::warn!("Java mods: using a block failed: {e}");
                        None
                    }
                }
            }
            #[cfg(not(feature = "java-mods"))]
            {
                let _ = (pos, face, point, off_hand);
                None
            }
        }

        /// A click in the window of a Java mod's menu (a cabinet's): the menu runs it, in Java;
        /// false for other windows.
        pub(in crate::game) fn java_menu_click(
            &mut self,
            host: &mut LanHost,
            conn: u32,
            p: &play::ContainerClick,
        ) -> bool {
            #[cfg(feature = "java-mods")]
            {
                let Some(uuid) = java_window_of(host, conn, p.container) else {
                    return false;
                };
                let (slot, button, input) = (i32::from(p.slot), i32::from(p.button), p.input);
                let clicked = self.with_java_world(|| {
                    horizon_jvm::events::menu_click(&uuid, slot, button, input)
                });
                if let Err(e) = clicked {
                    log::warn!("Java mods: a click in a mod's menu failed: {e}");
                }
                self.apply_java_actions(Some(host));
                true
            }
            #[cfg(not(feature = "java-mods"))]
            {
                let _ = (host, conn, p);
                false
            }
        }

        /// The player closed the window of a Java mod's menu; false for other windows.
        pub(in crate::game) fn java_menu_closed(
            &mut self,
            host: &mut LanHost,
            conn: u32,
            window: i32,
        ) -> bool {
            #[cfg(feature = "java-mods")]
            {
                let Some(uuid) = java_window_of(host, conn, window) else {
                    return false;
                };
                JAVA_WINDOWS
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&uuid);
                let closed = self.with_java_world(|| horizon_jvm::events::menu_close(&uuid));
                if let Err(e) = closed {
                    log::warn!("Java mods: closing a mod's menu failed: {e}");
                }
                self.apply_java_actions(Some(host));
                true
            }
            #[cfg(not(feature = "java-mods"))]
            {
                let _ = (host, conn, window);
                false
            }
        }

        /// `PlayerBlockBreakEvents.BEFORE` for the player acting now, who is about to break
        /// `state` at `pos`: false when a Java mod cancels the break. What the mods change
        /// (a tree feller's logs) is made with this tick's other actions.
        pub(in crate::game) fn java_before_break(
            &mut self,
            pos: [i32; 3],
            state: BlockStateId,
        ) -> bool {
            #[cfg(feature = "java-mods")]
            {
                if !java_mods_on() || !self.hears_players() {
                    return true;
                }
                let uuid = self
                    .lan_acting_uuid
                    .map(uuid_string)
                    .unwrap_or_else(|| self.player_uuid_string());
                let state = state_string(self.pack.assets.registry, state);
                let dim = self.dim.id();
                let allowed = self
                    .with_java_world(|| horizon_jvm::events::before_break(&uuid, dim, pos, &state));
                self.apply_java_actor_slots(&uuid);
                match allowed {
                    Ok(allowed) => allowed,
                    Err(e) => {
                        log::warn!("Java mods: a block break failed: {e}");
                        true
                    }
                }
            }
            #[cfg(not(feature = "java-mods"))]
            {
                let _ = (pos, state);
                true
            }
        }

        /// Lends the levels' chunks to capability `world` for the calls up to
        /// [`Game::return_world`], when a running server mod has it.
        fn lend_world(&mut self) {
            if !self.mods.world {
                return;
            }
            let mut dims = vec![(self.dim.id(), std::mem::take(&mut self.world.chunks))];
            for (dim, chunks) in self.levels.parked_chunks() {
                dims.push((dim.id(), std::mem::take(chunks)));
            }
            let registry = self.pack.assets.registry;
            let biomes = self.res.biomes().clone();
            self.mods.mods.lend_world(Box::new(LentWorld {
                registry,
                biomes,
                dims,
            }));
        }

        /// Puts back the chunks [`Game::lend_world`] lent.
        fn return_world(&mut self) {
            let Some(view) = self.mods.mods.take_world() else {
                return;
            };
            let view: Box<dyn std::any::Any> = view;
            let Ok(lent) = view.downcast::<LentWorld>() else {
                return;
            };
            let mut dims = lent.dims;
            if let Some(i) = dims.iter().position(|(d, _)| *d == self.dim.id()) {
                self.world.chunks = dims.swap_remove(i).1;
            }
            for (dim, chunks) in self.levels.parked_chunks() {
                if let Some(i) = dims.iter().position(|(d, _)| *d == dim.id()) {
                    *chunks = dims.swap_remove(i).1;
                }
            }
        }

        /// Makes the blocks server mods set in the level in place, in order,
        /// as `/setblock` does. Those of other dimensions wait for their level.
        pub(in crate::game) fn apply_mod_block_writes(&mut self) {
            #[cfg(feature = "java-mods")]
            self.apply_java_drops();
            if self.mods.block_writes.is_empty() {
                return;
            }
            let dim = self.dim.id();
            let (now, later): (Vec<_>, Vec<_>) = std::mem::take(&mut self.mods.block_writes)
                .into_iter()
                .partition(|(d, _)| d == dim);
            self.mods.block_writes = later;
            let registry = self.pack.assets.registry;
            for (_, b) in now {
                let state = match crate::game::render_control::parse_state(registry, &b.state) {
                    Ok(state) => state,
                    Err(e) => {
                        log::warn!("Horizon: a server mod set {}: {e}", b.state);
                        continue;
                    }
                };
                if let Err(e) = self.place_state([b.x, b.y, b.z], state, false) {
                    log::debug!(
                        "Horizon: a server mod's block at {} {} {}: {e:?}",
                        b.x,
                        b.y,
                        b.z
                    );
                }
            }
        }

        /// The items Java mods dropped and the level events they made in the level in place: Horizon's item entities, with
        /// the motion the mod gave them and vanilla's pickup delay (`ItemEntity.setDefaultPickUpDelay`).
        #[cfg(feature = "java-mods")]
        fn apply_java_drops(&mut self) {
            let dim = self.dim.id();
            let now: Vec<horizon_jvm::events::Action> = {
                let mut drops = JAVA_DROPS.lock().unwrap_or_else(|e| e.into_inner());
                if drops.is_empty() {
                    return;
                }
                let (now, later): (Vec<_>, Vec<_>) = std::mem::take(&mut *drops)
                    .into_iter()
                    .partition(|(d, _)| d == dim);
                *drops = later;
                now.into_iter().map(|(_, a)| a).collect()
            };
            for drop in now {
                if let horizon_jvm::events::Action::LevelEvent {
                    id,
                    position,
                    data,
                    state,
                    ..
                } = &drop
                {
                    // The state a 2001 names, by name: Horizon's own id for it.
                    let data = match state {
                        Some(state) => {
                            match crate::game::render_control::parse_state(
                                self.pack.assets.registry,
                                state,
                            ) {
                                Ok(state) => state.0 as i32,
                                Err(_) => continue,
                            }
                        }
                        None => *data,
                    };
                    self.level_event_at(*id, *position, data);
                    continue;
                }
                let horizon_jvm::events::Action::DropItem {
                    position,
                    velocity,
                    item,
                    count,
                    ..
                } = drop
                else {
                    continue;
                };
                if self.res.items().get(&item).is_none() || count <= 0 {
                    log::debug!("Java mods: a mod dropped {count} {item}, not an item here");
                    continue;
                }
                let stack = horizon_client::items::ItemStack::new(&item, count as u32);
                self.spawn_item(stack, position, velocity, 10);
            }
        }

        /// A player broke (or placed) `state` at `pos`: the player acting now.
        pub(in crate::game) fn mod_block_event(
            &mut self,
            pos: [i32; 3],
            state: BlockStateId,
            placed: bool,
        ) {
            if !self.hears_players() {
                return;
            }
            let who = self.lan_acting.map_or(Who::Local, Who::Conn);
            let state = self.state_string(state);
            self.mods.events.push(ModEvent::Block {
                who,
                pos,
                state,
                placed,
            });
        }

        /// A chat message a player sent.
        pub(in crate::game) fn mod_player_chat(&mut self, who: Who, text: &str) {
            if self.hears_players() {
                self.mods.events.push(ModEvent::Chat(who, text.to_owned()));
            }
        }

        /// `/line` typed by the window's own player in-frame: true when a
        /// server mod owns it.
        pub(in crate::game) fn local_server_mod_command(&mut self, line: &str) -> bool {
            if !self.hears_players() {
                return false;
            }
            let players = self.server_players(None);
            let Some(me) = players.first().cloned() else {
                return false;
            };
            if self.java_command(None, &me.id, line) {
                return true;
            }
            if !self.mods.server {
                return false;
            }
            let snapshot = self.mod_snapshot_with(players);
            self.mods.mods.set_snapshot(snapshot);
            self.lend_world();
            let owned = self.mods.mods.server_command(&me, line);
            self.return_world();
            self.show_mod_output();
            self.apply_mod_block_writes();
            owned
        }

        /// `/line` typed by a connection's player: true when a server mod
        /// owns it, whatever the player's permission level.
        pub(in crate::game) fn guest_server_mod_command(
            &mut self,
            host: &mut LanHost,
            conn: u32,
            line: &str,
        ) -> bool {
            if !self.hears_players() {
                return false;
            }
            let Some(g) = host.hub.guest(conn) else {
                return false;
            };
            let id = uuid_string(g.uuid);
            let players = self.server_players(Some(host));
            let Some(me) = players.iter().find(|p| p.id == id).cloned() else {
                return false;
            };
            if self.java_command(Some(&mut *host), &me.id, line) {
                return true;
            }
            if !self.mods.server {
                return false;
            }
            let snapshot = self.mod_snapshot_with(players);
            self.mods.mods.set_snapshot(snapshot);
            self.lend_world();
            let owned = self.mods.mods.server_command(&me, line);
            self.return_world();
            if !owned {
                return false;
            }
            self.route_mod_output(Some(host));
            self.apply_mod_block_writes();
            true
        }

        /// Sends a server mod's line to the player with this id: the
        /// window's own chat for the local player, a system message for a
        /// connection's. `host` is the hub when it is out of `self.lan`.
        pub(in crate::game) fn tell_server_player(
            &mut self,
            host: Option<&mut LanHost>,
            id: &str,
            line: Text,
        ) {
            let local = self.options.execution_role.is_client()
                && self.remote.is_none()
                && self.player.is_some();
            if local && self.player_uuid_string() == id {
                self.add_mod_chat_line(line);
                return;
            }
            let Some(host) = host.or(self.lan.as_mut()) else {
                return;
            };
            let to = host
                .hub
                .guests
                .iter()
                .find(|g| g.placed && uuid_string(g.uuid) == id);
            if let Some(conn) = to.map(|g| g.conn) {
                host.hub.send_to(
                    conn,
                    &C::SystemChat(play::SystemChat {
                        content: component_nbt(&line.component()),
                        overlay: false,
                    }),
                );
            }
        }

        /// Routes a server mod's output: `Tell` and `Reply` to their player,
        /// `Broadcast` to everyone and the log.
        pub(in crate::game) fn route_server_output(
            &mut self,
            mut host: Option<&mut LanHost>,
            output: Output,
        ) {
            let named = |name: &str, text: String| {
                let mut line = Text::literal(format!("[{name}] ")).with_color(text::GRAY);
                line.push(Text::literal(text));
                line
            };
            match output {
                Output::Tell { name, player, text } => {
                    self.tell_server_player(host, &player, named(&name, text));
                }
                Output::Reply {
                    player,
                    text,
                    error,
                } => {
                    let line = Text::literal(text);
                    let line = if error {
                        line.with_color(text::RED)
                    } else {
                        line
                    };
                    self.tell_server_player(host, &player, line);
                }
                Output::Broadcast { name, text } => {
                    let plain = format!("[{name}] {text}");
                    if self.options.dedicated.is_some() {
                        super::super::dedicated::log("INFO", &plain);
                    } else {
                        log::info!("{plain}");
                    }
                    for p in self.server_players(host.as_deref()) {
                        let line = named(&name, text.clone());
                        self.tell_server_player(host.as_deref_mut(), &p.id, line);
                    }
                }
                Output::SetBlocks {
                    name,
                    dimension,
                    blocks,
                } => {
                    let queued = &mut self.mods.block_writes;
                    queued.extend(blocks.into_iter().map(|b| (dimension.clone(), b)));
                    if queued.len() > MAX_QUEUED_WRITES {
                        let over = queued.len() - MAX_QUEUED_WRITES;
                        queued.drain(..over);
                        log::warn!(
                            "Horizon: mod {name} set more blocks than wait for their level: {over} dropped"
                        );
                    }
                }
                _ => {}
            }
        }
    }
}
