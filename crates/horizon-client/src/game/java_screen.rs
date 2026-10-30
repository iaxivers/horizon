//! Java mods' own screens (Farmer's Delight's cooking pot's): the mod's client code draws them in
//! horizon-jvm's headless client, whose frames (operations: images, GUI sprites, rectangles,
//! text, items) reach the player's game on [`CHANNEL`]; the game draws them in place of its own
//! container screen and sends the mouse
//! and keys back on [`INPUT`], which the screen runs.
use super::{Game, Screen, lan::LanHost};
use horizon_client::hud::{HudBatch, rgba};
use horizon_client::items::ItemStack;
use horizon_protocol::packets::{
    configuration::CustomPayload,
    play::{Clientbound as C, Serverbound as S},
};
use serde_json::{Value, json};
use winit::keyboard::KeyCode;

/// A frame, server to client: `{"window": id, "ops": [...]}`.
pub(super) const CHANNEL: &str = "horizon:java_screen";
/// Input, client to server: `{"window": id, "kind": ..., ...}` (HorizonClient.input's).
pub(super) const INPUT: &str = "horizon:java_screen_input";
/// Server to game: the keys Java mods' key bindings are set to (a JSON array of 26.3's
/// scancodes), sent once as a player joins. The game then sends those keys while playing on
/// [`INPUT`] (`{"kind": "bind", "key": scancode, "down": bool}`), as vanilla's keyboard handler
/// clicks a `KeyMapping` with no screen open.
pub(super) const KEYS: &str = "horizon:java_keys";

/// The keys a server's Java mods bind, and which of them this game told it are down.
#[derive(Default)]
pub(super) struct JavaBinds {
    keys: std::collections::HashSet<i32>,
    down: std::collections::HashSet<i32>,
}
/// The window of a client-only screen (a mod's `Gui.setScreen` with no menu: its settings, a
/// guide book): no menu's window is open under it. Its frames say this window, and
/// `{"window": -1, "close": true}` closes it.
pub(super) const CLIENT_WINDOW: i32 = -1;

/// The mod screen a window shows, as its last frame drew it.
#[derive(Default)]
pub(super) struct JavaScreen {
    window: i32,
    ops: Vec<Value>,
    /// What the server last heard: the mouse, and the GUI's size.
    sent_mouse: Option<(i32, i32)>,
    sent_size: Option<(i32, i32)>,
    /// A text box of the screen has the keyboard (the frame's `typing`): it takes the
    /// inventory key's letter too.
    typing: bool,
}

/// Java's ARGB as the batch's colour.
fn argb(c: i64) -> u32 {
    let c = c as u32;
    rgba((c >> 16) as u8, (c >> 8) as u8, c as u8, (c >> 24) as u8)
}

fn int(o: &Value, key: &str) -> f32 {
    o.get(key).and_then(Value::as_f64).unwrap_or(0.0) as f32
}

/// The atlas key of a texture id: `minecraft:textures/gui/x.png` is `gui/x`, another
/// namespace's `ns:gui/x` (HudAtlas packs mods' `textures/gui/` under their namespace).
fn texture_key(id: &str) -> String {
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

/// A GUI sprite's key: `textures/gui/sprites/<path>` of its namespace.
fn sprite_key(id: &str) -> String {
    let (ns, path) = id.split_once(':').unwrap_or(("minecraft", id));
    texture_key(&format!("{ns}:gui/sprites/{path}"))
}

fn stack(o: &Value) -> Option<ItemStack> {
    let snbt = o.get("item")?.as_str()?;
    let (tag, _) = horizon_world::nbt::parse_snbt(snbt).ok()?;
    horizon_items::item_nbt::item_from_nbt(&tag)
}

impl Game<'_> {
    // ---- The player's game ----------------------------------------------------------------

    /// The keys the server's Java mods bind ([`KEYS`]).
    pub(super) fn remote_java_keys(&mut self, data: &[u8]) {
        if let Ok(keys) = serde_json::from_slice::<Vec<i32>>(data) {
            self.java_binds.keys = keys.into_iter().collect();
        }
    }

    /// A key of the window, for Java mods' key bindings: pressed while playing (no screen), or
    /// released after it was sent down (KeyboardHandler.keyPress: KeyMapping.set and click).
    pub(super) fn java_bind_key(&mut self, key: KeyCode, pressed: bool) {
        if self.java_binds.keys.is_empty() || self.remote.is_none() {
            return;
        }
        let Some(code) = sdl_key(key) else {
            return;
        };
        if !self.java_binds.keys.contains(&code) {
            return;
        }
        let send = if pressed {
            self.screen == Screen::Playing && self.java_binds.down.insert(code)
        } else {
            self.java_binds.down.remove(&code)
        };
        if send {
            let data = json!({"kind": "bind", "key": code, "down": pressed})
                .to_string()
                .into_bytes();
            self.send(S::CustomPayload(CustomPayload {
                channel: INPUT.into(),
                data,
            }));
        }
    }

    /// A frame of the open window's mod screen ([`CHANNEL`]).
    pub(super) fn remote_java_screen(&mut self, data: &[u8]) {
        let Ok(frame) = serde_json::from_slice::<Value>(data) else {
            return;
        };
        let window = frame.get("window").and_then(Value::as_i64).unwrap_or(-1) as i32;
        if frame.get("close").and_then(Value::as_bool) == Some(true) {
            // The mod closed its client-only screen (Gui.setScreen(null)).
            if self
                .java_screen
                .as_ref()
                .is_some_and(|s| s.window == window)
            {
                self.java_screen = None;
            }
            if self.screen == Screen::JavaMod {
                self.leave_java_screen();
            }
            return;
        }
        let ops = match frame.get("ops") {
            Some(Value::Array(ops)) => ops.clone(),
            _ => return,
        };
        let typing = ops
            .iter()
            .any(|op| op.get("op").and_then(Value::as_str) == Some("typing"));
        match &mut self.java_screen {
            Some(screen) if screen.window == window => {
                screen.ops = ops;
                screen.typing = typing;
            }
            _ => {
                self.java_screen = Some(JavaScreen {
                    window,
                    ops,
                    typing,
                    ..Default::default()
                })
            }
        }
        // A client-only screen is a screen of its own, over the world (or what was open).
        if window == CLIENT_WINDOW && self.screen != Screen::JavaMod {
            self.screen = Screen::JavaMod;
            self.capture(false);
        }
    }

    /// Back to the world from a client-only screen.
    fn leave_java_screen(&mut self) {
        self.screen = Screen::Playing;
        if self.player.is_some() || self.remote.is_some() {
            self.capture(true);
        }
    }

    /// The window left a client-only screen on its own (Escape with no screen to take it): the
    /// mod hears it closed.
    pub(super) fn close_java_screen(&mut self) {
        if let Some(screen) = self.java_screen.take()
            && screen.window == CLIENT_WINDOW
        {
            self.send_java_screen_input(CLIENT_WINDOW, json!({"kind": "close"}));
        }
        self.leave_java_screen();
    }

    /// The open window's mod screen, when it has one: the window the server opened is still
    /// the one open, and the inventory screen shows it.
    pub(super) fn java_screen_window(&self) -> Option<i32> {
        let screen = self.java_screen.as_ref()?;
        if self.screen == Screen::JavaMod {
            return (screen.window == CLIENT_WINDOW).then_some(CLIENT_WINDOW);
        }
        let open = self.remote.as_ref()?.menu.as_ref()?.0;
        (self.screen == Screen::Inventory && open == screen.window).then_some(screen.window)
    }

    /// Draws the open mod screen in place of the container screen: false when there is none.
    pub(super) fn draw_java_screen(&mut self, batch: &mut HudBatch) -> bool {
        let Some(window) = self.java_screen_window() else {
            return false;
        };
        // The screen lays out in this GUI size, and hovers where the mouse is.
        let size = (batch.width as i32, batch.height as i32);
        let mouse = (self.cursor.0 as i32, self.cursor.1 as i32);
        let screen = self.java_screen.as_mut().expect("checked");
        let (send_size, send_mouse) = (
            screen.sent_size != Some(size),
            screen.sent_mouse != Some(mouse),
        );
        screen.sent_size = Some(size);
        screen.sent_mouse = Some(mouse);
        if send_size {
            self.send_java_screen_input(
                window,
                json!({"kind": "size", "width": size.0, "height": size.1}),
            );
        }
        if send_mouse {
            self.send_java_screen_input(
                window,
                json!({"kind": "move", "x": mouse.0, "y": mouse.1}),
            );
        }
        let ops = self
            .java_screen
            .as_ref()
            .map(|s| s.ops.clone())
            .unwrap_or_default();
        let mut tooltip = None;
        let mut lines_tooltip = None;
        for o in &ops {
            // The GUI pose (Matrix3x2f: m00 m01 m10 m11 m20 m21). A translation moves the op;
            // anything more (a scale: Tom's Storage's small counts) transforms what it drew.
            let pose: [f32; 6] = match o.get("pose").and_then(Value::as_array) {
                Some(p) if p.len() == 6 => {
                    std::array::from_fn(|i| p[i].as_f64().unwrap_or(0.0) as f32)
                }
                _ => [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
            };
            let affine = pose[..4] != [1.0, 0.0, 0.0, 1.0];
            let (dx, dy) = if affine {
                (0.0, 0.0)
            } else {
                (pose[4], pose[5])
            };
            let start = batch.vertices.len();
            let rect = || {
                let (x0, y0) = (int(o, "x0") + dx, int(o, "y0") + dy);
                (x0, y0, int(o, "x1") + dx - x0, int(o, "y1") + dy - y0)
            };
            let atlas = &self.gui.atlas;
            match o.get("op").and_then(Value::as_str).unwrap_or_default() {
                "fill" => {
                    let (x, y, w, h) = rect();
                    let color = argb(o.get("color").and_then(Value::as_i64).unwrap_or(0));
                    batch.fill(atlas, x, y, w, h, color);
                }
                "gradient" => {
                    let top = argb(o.get("top").and_then(Value::as_i64).unwrap_or(0));
                    let bottom = argb(o.get("bottom").and_then(Value::as_i64).unwrap_or(0));
                    batch.fill_gradient(atlas, rect(), top, bottom);
                }
                kind @ ("blit" | "sprite") => {
                    let key = if kind == "blit" {
                        texture_key(o.get("texture").and_then(Value::as_str).unwrap_or_default())
                    } else {
                        sprite_key(o.get("sprite").and_then(Value::as_str).unwrap_or_default())
                    };
                    let Some(sprite) = atlas.sprites.get(&key).copied() else {
                        continue;
                    };
                    let (u, v) = (sprite.u1 - sprite.u0, sprite.v1 - sprite.v0);
                    let (u0, u1) = (int(o, "u0"), o.get("u1").map_or(1.0, |_| int(o, "u1")));
                    let (v0, v1) = (int(o, "v0"), o.get("v1").map_or(1.0, |_| int(o, "v1")));
                    let color = o
                        .get("color")
                        .and_then(Value::as_i64)
                        .map_or(u32::MAX, argb);
                    let (x, y, w, h) = rect();
                    // UVs past the texture repeat it (a menu background's 32x32 tile across
                    // the screen): one quad per repeat, as the atlas can't wrap.
                    let (du, dv) = (u1 - u0, v1 - v0);
                    let wraps = du.abs() > 1.0 || dv.abs() > 1.0 || u0 < 0.0 || v0 < 0.0;
                    if !wraps {
                        let mut r = sprite;
                        r.u0 = sprite.u0 + u0 * u;
                        r.u1 = sprite.u0 + u1 * u;
                        r.v0 = sprite.v0 + v0 * v;
                        r.v1 = sprite.v0 + v1 * v;
                        batch.rect_uv(x, y, w, h, r, color);
                    } else if du > 0.0 && dv > 0.0 {
                        // Texture units per pixel, and each tile's piece of [u0, u1].
                        let (sx, sy) = (w / du, h / dv);
                        let mut tu = u0;
                        while tu < u1 - 1e-4 {
                            let next_u = (tu.floor() + 1.0).min(u1);
                            let mut tv = v0;
                            while tv < v1 - 1e-4 {
                                let next_v = (tv.floor() + 1.0).min(v1);
                                let (fu, fv) = (tu - tu.floor(), tv - tv.floor());
                                let mut r = sprite;
                                r.u0 = sprite.u0 + fu * u;
                                r.u1 = sprite.u0 + (fu + next_u - tu) * u;
                                r.v0 = sprite.v0 + fv * v;
                                r.v1 = sprite.v0 + (fv + next_v - tv) * v;
                                batch.rect_uv(
                                    x + (tu - u0) * sx,
                                    y + (tv - v0) * sy,
                                    (next_u - tu) * sx,
                                    (next_v - tv) * sy,
                                    r,
                                    color,
                                );
                                tv = next_v;
                            }
                            tu = next_u;
                        }
                    }
                }
                "text" => {
                    let (x, y) = (int(o, "x") + dx, int(o, "y") + dy);
                    let color = argb(o.get("color").and_then(Value::as_i64).unwrap_or(-1));
                    let shadow = o.get("shadow").and_then(Value::as_bool).unwrap_or(false);
                    if let Some(text) = o.get("text").and_then(Value::as_str) {
                        batch.text(atlas, text, x, y, color, shadow);
                    } else if let Some(json) = o.get("json").and_then(Value::as_str)
                        && let Ok(value) = serde_json::from_str::<Value>(json)
                    {
                        let text = self.translated(horizon_client::scoreboard::comp_text(&value));
                        batch.text_styled(atlas, &text, x, y, color, shadow);
                    }
                }
                "item" => {
                    if let Some(stack) = stack(o) {
                        self.draw_item_model(batch, &stack, int(o, "x") + dx, int(o, "y") + dy);
                    }
                }
                "decorations" => {
                    if let Some(stack) = stack(o) {
                        let count = o.get("count").and_then(Value::as_str).map(str::to_owned);
                        self.draw_item_decorations(
                            batch,
                            &stack,
                            int(o, "x") + dx,
                            int(o, "y") + dy,
                            count,
                        );
                    }
                }
                "item_tooltip" => tooltip = stack(o),
                "tooltip" => {
                    let lines: Vec<horizon_client::text::Text> = o
                        .get("lines")
                        .and_then(Value::as_array)
                        .into_iter()
                        .flatten()
                        .filter_map(|l| serde_json::from_str::<Value>(l.as_str()?).ok())
                        .map(|v| self.translated(horizon_client::scoreboard::comp_text(&v)))
                        .collect();
                    lines_tooltip = Some(lines);
                }
                _ => {}
            }
            if affine {
                for v in &mut batch.vertices[start..] {
                    let [x, y] = [v.pos[0], v.pos[1]];
                    v.pos[0] = pose[0] * x + pose[2] * y + pose[4];
                    v.pos[1] = pose[1] * x + pose[3] * y + pose[5];
                }
            }
        }
        if let Some(stack) = tooltip {
            let (x, y) = self.cursor;
            self.draw_stack_tooltip(batch, &stack, x, y);
        }
        if let Some(lines) = lines_tooltip {
            horizon_client::ui::tooltip(batch, &self.gui.atlas, &lines, self.cursor);
        }
        true
    }

    /// A component as the screen shows it: its translations resolved in the game's language.
    fn translated(&self, text: horizon_client::text::Text) -> horizon_client::text::Text {
        text.resolve_legacy()
    }

    fn send_java_screen_input(&mut self, window: i32, mut input: Value) {
        input["window"] = json!(window);
        let data = input.to_string().into_bytes();
        self.send(S::CustomPayload(CustomPayload {
            channel: INPUT.into(),
            data,
        }));
    }

    /// Mouse and keys over the open mod screen go to it: false when none is open.
    pub(super) fn java_screen_mouse(&mut self, kind: &str, right: bool) -> bool {
        let Some(window) = self.java_screen_window() else {
            return false;
        };
        let (x, y) = self.cursor;
        let mods = if self.has_shift_down() { SHIFT } else { 0 };
        self.send_java_screen_input(
            window,
            // 26.3's buttons count from one (InputConstants.MOUSE_BUTTON_LEFT, _RIGHT).
            json!({"kind": kind, "x": x, "y": y, "button": if right { 3 } else { 1 }, "mods": mods}),
        );
        true
    }

    pub(super) fn java_screen_scroll(&mut self, h: f64, v: f64) -> bool {
        let Some(window) = self.java_screen_window() else {
            return false;
        };
        let (x, y) = self.cursor;
        self.send_java_screen_input(
            window,
            json!({"kind": "scroll", "x": x, "y": y, "h": h, "v": v}),
        );
        true
    }

    /// A key for the open mod screen (GLFW's key code); Escape and the inventory key stay the
    /// game's, closing the window.
    /// Whether a mod screen takes this key: every key but Escape and the inventory key, which
    /// stay Horizon's (they close the menu) unless one of its text boxes is typed in, and none
    /// while Horizon's own recipe book search is.
    pub(super) fn java_screen_takes(&self, key: KeyCode) -> bool {
        let Some(screen) = &self.java_screen else {
            return false;
        };
        // A client-only screen takes every key, Escape too: the screen closes itself (to its
        // parent, for a settings page), as vanilla's Screen.keyPressed does.
        if self.screen == Screen::JavaMod {
            return screen.window == CLIENT_WINDOW;
        }
        key != KeyCode::Escape
            && (screen.typing || self.settings.keys.translate(key) != Some(Ok(KeyCode::KeyE)))
            && !self.recipe_search_focused()
            && self.java_screen_window().is_some()
    }

    /// A key press on a mod screen: `Screen.keyPressed` with 26.3's key code, then the
    /// typed characters (`charTyped`), as vanilla's keyboard handler sends both.
    pub(super) fn java_screen_key(&mut self, key: KeyCode, text: Option<&str>) -> bool {
        let Some(window) = self.java_screen_window() else {
            return false;
        };
        let mut mods = 0;
        if self.has_shift_down() {
            mods |= SHIFT;
        }
        if self.has_control_down() {
            mods |= CONTROL;
        }
        if let Some(code) = sdl_key(key) {
            self.send_java_screen_input(
                window,
                json!({"kind": "key", "key": code, "keycode": sdl_keycode(key, code), "mods": mods}),
            );
        }
        for ch in text.unwrap_or("").chars().filter(|c| !c.is_control()) {
            self.send_java_screen_input(window, json!({"kind": "char", "char": ch as u32}));
        }
        true
    }

    // ---- The server -----------------------------------------------------------------------

    /// A player's client-only screen closed, from Java: their game leaves it.
    #[cfg_attr(not(feature = "java-mods"), allow(dead_code))]
    pub(super) fn send_java_screen_close(host: &mut LanHost, conn: u32) {
        let data = format!("{{\"window\":{CLIENT_WINDOW},\"close\":true}}").into_bytes();
        host.hub.send_to(
            conn,
            &C::CustomPayload(CustomPayload {
                channel: CHANNEL.into(),
                data,
            }),
        );
    }

    /// A frame of a player's mod screen, from Java: to their game, for their window.
    #[cfg_attr(not(feature = "java-mods"), allow(dead_code))]
    pub(super) fn send_java_screen_frame(host: &mut LanHost, conn: u32, window: i32, frame: &str) {
        let data = format!("{{\"window\":{window},\"ops\":{frame}}}").into_bytes();
        host.hub.send_to(
            conn,
            &C::CustomPayload(CustomPayload {
                channel: CHANNEL.into(),
                data,
            }),
        );
    }
}

/// The text a letter, digit or space key types (scripted presses carry none of their own).
pub(super) fn key_text(key: KeyCode) -> Option<String> {
    let name = format!("{key:?}");
    if key == KeyCode::Space {
        return Some(" ".into());
    }
    let ch = name
        .strip_prefix("Key")
        .or_else(|| name.strip_prefix("Digit"))
        .filter(|rest| rest.len() == 1)?;
    Some(ch.to_lowercase())
}

/// SDL3's key code for a key (`KeyEvent.shortcutKey`, what text boxes' editing keys and
/// shortcuts match): a character key's character, others their scancode with bit 30 set.
fn sdl_keycode(key: KeyCode, scancode: i32) -> i32 {
    use KeyCode as K;
    if let Some(text) = key_text(key) {
        return text.chars().next().map_or(scancode, |c| c as i32);
    }
    match key {
        K::Enter => 13,
        K::Escape => 27,
        K::Backspace => 8,
        K::Tab => 9,
        K::Delete => 127,
        K::Minus => '-' as i32,
        K::Equal => '=' as i32,
        K::BracketLeft => '[' as i32,
        K::BracketRight => ']' as i32,
        K::Backslash => '\\' as i32,
        K::Semicolon => ';' as i32,
        K::Quote => '\'' as i32,
        K::Backquote => '`' as i32,
        K::Comma => ',' as i32,
        K::Period => '.' as i32,
        K::Slash => '/' as i32,
        _ => scancode | 1 << 30,
    }
}

/// `InputConstants.MOD_SHIFT` and `MOD_CONTROL` (SDL3's `KMOD_SHIFT`, `KMOD_CTRL`).
const SHIFT: i32 = 3;
const CONTROL: i32 = 192;

/// winit's key → 26.3's key code (`InputConstants.KEY_*`: SDL3 scancodes, the USB HID usage
/// numbers), what Java screens compare against.
fn sdl_key(key: KeyCode) -> Option<i32> {
    use KeyCode as K;
    let letters = [
        K::KeyA,
        K::KeyB,
        K::KeyC,
        K::KeyD,
        K::KeyE,
        K::KeyF,
        K::KeyG,
        K::KeyH,
        K::KeyI,
        K::KeyJ,
        K::KeyK,
        K::KeyL,
        K::KeyM,
        K::KeyN,
        K::KeyO,
        K::KeyP,
        K::KeyQ,
        K::KeyR,
        K::KeyS,
        K::KeyT,
        K::KeyU,
        K::KeyV,
        K::KeyW,
        K::KeyX,
        K::KeyY,
        K::KeyZ,
    ];
    let digits = [
        K::Digit1,
        K::Digit2,
        K::Digit3,
        K::Digit4,
        K::Digit5,
        K::Digit6,
        K::Digit7,
        K::Digit8,
        K::Digit9,
        K::Digit0,
    ];
    let numpad = [
        K::Numpad1,
        K::Numpad2,
        K::Numpad3,
        K::Numpad4,
        K::Numpad5,
        K::Numpad6,
        K::Numpad7,
        K::Numpad8,
        K::Numpad9,
        K::Numpad0,
    ];
    let functions = [
        K::F1,
        K::F2,
        K::F3,
        K::F4,
        K::F5,
        K::F6,
        K::F7,
        K::F8,
        K::F9,
        K::F10,
        K::F11,
        K::F12,
    ];
    let at =
        |keys: &[KeyCode], base: i32| keys.iter().position(|k| *k == key).map(|i| base + i as i32);
    if let Some(code) = at(&letters, 4)
        .or_else(|| at(&digits, 30))
        .or_else(|| at(&numpad, 89))
        .or_else(|| at(&functions, 58))
    {
        return Some(code);
    }
    Some(match key {
        K::Enter => 40,
        K::Escape => 41,
        K::Backspace => 42,
        K::Tab => 43,
        K::Space => 44,
        K::Minus => 45,
        K::Equal => 46,
        K::BracketLeft => 47,
        K::BracketRight => 48,
        K::Backslash => 49,
        K::Semicolon => 51,
        K::Quote => 52,
        K::Backquote => 53,
        K::Comma => 54,
        K::Period => 55,
        K::Slash => 56,
        K::CapsLock => 57,
        K::Insert => 73,
        K::Home => 74,
        K::PageUp => 75,
        K::Delete => 76,
        K::End => 77,
        K::PageDown => 78,
        K::ArrowRight => 79,
        K::ArrowLeft => 80,
        K::ArrowDown => 81,
        K::ArrowUp => 82,
        K::NumLock => 83,
        K::NumpadDivide => 84,
        K::NumpadMultiply => 85,
        K::NumpadSubtract => 86,
        K::NumpadAdd => 87,
        K::NumpadEnter => 88,
        K::NumpadDecimal => 99,
        K::ControlLeft => 224,
        K::ShiftLeft => 225,
        K::AltLeft => 226,
        K::SuperLeft => 227,
        K::ControlRight => 228,
        K::ShiftRight => 229,
        K::AltRight => 230,
        K::SuperRight => 231,
        _ => return None,
    })
}
