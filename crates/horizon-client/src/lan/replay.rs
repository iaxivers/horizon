//! Horizon's replay viewer: a recording of ReplayMod's (`.mcpr`) played to Horizon's own client
//! as a server would send it, so Horizon's renderer shows it.
//!
//! An `.mcpr` is a zip: `metaData.json` (`protocol`: the game's protocol number) and
//! `recording.tmcpr`, every packet the recording client received, each as its time (ms since
//! the start, an int), its length (an int) and its body (packet id and data, unframed), from
//! the login's end (`ClientboundLoginFinishedPacket`) through configuration into play. The
//! viewer serves it over one end of a memory pipe: its own login's end, the recorded
//! configuration, then the play packets at their times.
//!
//! The window joins as a spectator watching: it is put in spectator mode (flying) after each
//! login and respawn, and what would act on the viewer instead of showing the recording is
//! left out (the recorder's health and death, screens it opened, teleports after the first,
//! game mode and abilities, disconnects, reconfiguration, transfers). Chat lines control it:
//! `pause`, `play`, `speed <x>`, `skip <seconds>`.

use horizon_protocol::{
    PROTOCOL_VERSION,
    connection::{Connection, Pipe},
    crypto::offline_uuid,
    ids,
    packets::{login, play},
    text::translatable,
};
use std::io::Read;
use std::path::Path;
use std::sync::mpsc;
use std::time::{Duration, Instant};

/// A recording read from its file.
pub struct Recording {
    /// The configuration's bodies, through `ClientboundFinishConfigurationPacket`.
    pub configuration: Vec<Vec<u8>>,
    /// The play bodies and their times (ms since the recording's start).
    pub play: Vec<(u32, Vec<u8>)>,
}

fn varint(b: &[u8]) -> Option<(i32, usize)> {
    let mut value = 0i32;
    for (i, byte) in b.iter().enumerate().take(5) {
        value |= i32::from(byte & 0x7f) << (7 * i);
        if byte & 0x80 == 0 {
            return Some((value, i + 1));
        }
    }
    None
}

impl Recording {
    /// Reads `path` (an `.mcpr`). A recording of another protocol than this game's is refused:
    /// its packets would be read as this version's.
    pub fn read(path: &Path) -> Result<Self, String> {
        let file = std::fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let mut zip = zip::ZipArchive::new(file).map_err(|e| format!("{}: {e}", path.display()))?;
        let mut text = String::new();
        zip.by_name("metaData.json")
            .map_err(|e| format!("metaData.json: {e}"))?
            .read_to_string(&mut text)
            .map_err(|e| e.to_string())?;
        let meta: serde_json::Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
        let protocol = meta["protocol"].as_i64().unwrap_or(-1);
        if protocol != i64::from(PROTOCOL_VERSION) {
            return Err(format!(
                "recorded with protocol {protocol} ({}), this game plays {PROTOCOL_VERSION}",
                meta["mcversion"].as_str().unwrap_or("?")
            ));
        }
        let mut data = Vec::new();
        zip.by_name("recording.tmcpr")
            .map_err(|e| format!("recording.tmcpr: {e}"))?
            .read_to_end(&mut data)
            .map_err(|e| e.to_string())?;
        Self::parse(&data)
    }

    /// The packets of a `recording.tmcpr`.
    pub fn parse(data: &[u8]) -> Result<Self, String> {
        let mut at = 0;
        let (mut configuration, mut play) = (Vec::new(), Vec::new());
        // 0 login, 1 configuration, 2 play.
        let mut phase = 0;
        while at + 8 <= data.len() {
            let time = u32::from_be_bytes(data[at..at + 4].try_into().expect("4 bytes"));
            let len = i32::from_be_bytes(data[at + 4..at + 8].try_into().expect("4 bytes"));
            at += 8;
            let len = usize::try_from(len).map_err(|_| "a negative length".to_string())?;
            let body = data
                .get(at..at + len)
                .ok_or("the recording ends inside a packet")?
                .to_vec();
            at += len;
            let Some((id, _)) = varint(&body) else {
                continue;
            };
            match phase {
                0 => {
                    if id == ids::login::clientbound::LOGIN_FINISHED {
                        phase = 1;
                    }
                }
                1 => {
                    let finished = id == ids::configuration::clientbound::FINISH_CONFIGURATION;
                    configuration.push(body);
                    if finished {
                        phase = 2;
                    }
                }
                _ => play.push((time, body)),
            }
        }
        if phase < 2 {
            return Err("the recording has no play phase".into());
        }
        Ok(Self {
            configuration,
            play,
        })
    }
}

/// Opens `path` and serves it on a new thread: the end of the pipe the window joins through
/// (`Game::connect_local`).
pub fn open(path: &Path) -> Result<Pipe, String> {
    let recording = Recording::read(path)?;
    let (client, server) = Pipe::pair();
    std::thread::Builder::new()
        .name("horizon-replay".into())
        .spawn(move || {
            if let Err(e) = serve(Connection::local(server), &recording) {
                log::info!("[replay] ended: {e}");
            }
        })
        .map_err(|e| e.to_string())?;
    Ok(client)
}

/// What the viewer was told on chat.
enum Control {
    Pause,
    Play,
    Speed(f64),
    Skip(f64),
}

fn control(line: &str) -> Option<Control> {
    let mut words = line.split_whitespace();
    let word = words.next()?.trim_start_matches(['/', '.']);
    let number = words.next().and_then(|w| w.parse::<f64>().ok());
    Some(match word {
        "pause" => Control::Pause,
        "play" | "resume" => Control::Play,
        "speed" => Control::Speed(number?.clamp(0.05, 20.0)),
        "skip" => Control::Skip(number?.max(0.0)),
        _ => return None,
    })
}

/// Play packets the viewer leaves out: they would act on the viewer's own player or window.
fn left_out(id: i32, body: &[u8]) -> bool {
    use ids::play::clientbound as p;
    match id {
        // ClientboundGameEventPacket CHANGE_GAME_MODE (3): the viewer stays a spectator.
        p::GAME_EVENT => body.get(1) == Some(&3),
        p::PLAYER_ABILITIES
        | p::SET_HEALTH
        | p::PLAYER_COMBAT_KILL
        | p::OPEN_SCREEN
        | p::MERCHANT_OFFERS
        | p::OPEN_BOOK
        | p::OPEN_SIGN_EDITOR
        | p::MOUNT_SCREEN_OPEN
        | p::SET_CAMERA
        | p::DISCONNECT
        | p::START_CONFIGURATION
        | p::TRANSFER
        | p::SHOW_DIALOG
        | p::PLAYER_ROTATION => true,
        // Horizon's own window traffic to the recorder's game (a native sidebar, mods' HUDs):
        // not the world's.
        p::CUSTOM_PAYLOAD => channel(body).is_some_and(|c| c.starts_with("horizon:")),
        _ => false,
    }
}

/// A custom payload's channel.
fn channel(body: &[u8]) -> Option<&str> {
    let (_, id_len) = varint(body)?;
    let (len, len_len) = varint(&body[id_len..])?;
    let start = id_len + len_len;
    std::str::from_utf8(body.get(start..start + usize::try_from(len).ok()?)?).ok()
}

fn tell(key: &str, args: &[&str]) -> Vec<u8> {
    play::Clientbound::SystemChat(play::SystemChat {
        content: translatable(key, args),
        overlay: false,
    })
    .encode()
}

/// After a login or respawn: the viewer flies as a spectator.
fn spectate() -> [Vec<u8>; 2] {
    [
        play::Clientbound::GameEvent(play::GameEvent {
            event: 3,
            param: 3.0,
        })
        .encode(),
        // Invulnerable, flying, may fly.
        play::Clientbound::PlayerAbilities(play::PlayerAbilities {
            flags: 0x07,
            flying_speed: 0.05,
            walking_speed: 0.1,
        })
        .encode(),
    ]
}

/// The recorder's first position, moved 4 blocks back and 2 up (absolute coordinates only):
/// the viewer starts behind the recorder's body, looking where it looks, not inside its head.
fn behind(id: i32, body: &[u8]) -> Vec<u8> {
    let Some((_, at)) = varint(body) else {
        return body.to_vec();
    };
    match play::Clientbound::decode(id, &body[at..]) {
        Ok(play::Clientbound::PlayerPosition(mut p)) if p.relatives & 0x7 == 0 => {
            let yaw = f64::from(p.yaw).to_radians();
            // Minecraft's facing: yaw 0 looks south (+z), 90 west (-x).
            p.position[0] += yaw.sin() * 4.0;
            p.position[1] += 2.0;
            p.position[2] -= yaw.cos() * 4.0;
            play::Clientbound::PlayerPosition(p).encode()
        }
        _ => body.to_vec(),
    }
}

fn keep_alive(n: i64) -> Vec<u8> {
    let mut body = vec![ids::play::clientbound::KEEP_ALIVE as u8];
    body.extend_from_slice(&n.to_be_bytes());
    body
}

fn serve(c: Connection, recording: &Recording) -> Result<(), String> {
    let e = |e: horizon_protocol::Error| e.to_string();
    let mut c = c;
    // The handshake, then the client's hello.
    c.recv().map_err(e)?;
    let (id, payload) = c.recv().map_err(e)?;
    let name = match login::Serverbound::decode(id, &payload).map_err(e)? {
        login::Serverbound::Hello(h) => h.name,
        _ => return Err("no login".into()),
    };
    // The viewer's own profile, not the recorder's: the recorder is in the recording.
    let uuid = offline_uuid(&format!("{name} (replay)"));
    c.send(
        &login::Clientbound::LoginFinished(login::LoginFinished {
            profile: login::GameProfile {
                uuid,
                name,
                properties: Vec::new(),
            },
            session: uuid,
        })
        .encode(),
    )
    .map_err(e)?;
    loop {
        let (id, payload) = c.recv().map_err(e)?;
        if matches!(
            login::Serverbound::decode(id, &payload),
            Ok(login::Serverbound::LoginAcknowledged(_))
        ) {
            break;
        }
    }
    let (mut reader, mut writer) = c.split();
    // What the client sends: read so it never waits; its chat lines control the replay.
    let (tx, rx) = mpsc::channel();
    std::thread::Builder::new()
        .name("horizon-replay-input".into())
        .spawn(move || {
            let mut playing = false;
            while let Ok((id, payload)) = reader.recv() {
                if !playing {
                    playing = id == ids::configuration::serverbound::FINISH_CONFIGURATION;
                    continue;
                }
                let line = match play::Serverbound::decode(id, &payload) {
                    Ok(play::Serverbound::Chat(chat)) => chat.message,
                    Ok(play::Serverbound::ChatCommand(c)) => c.command,
                    _ => continue,
                };
                if let Some(c) = control(&line)
                    && tx.send(c).is_err()
                {
                    return;
                }
            }
        })
        .map_err(|e| e.to_string())?;
    for body in &recording.configuration {
        writer.send(body).map_err(e)?;
    }
    let start = recording.play.first().map_or(0, |(t, _)| *t);
    let total = recording.play.last().map_or(0, |(t, _)| *t) - start;
    // The replay's clock (ms of the recording), moved by real time at the chosen speed.
    let (mut clock, mut speed, mut paused) = (0f64, 1f64, false);
    let mut last = Instant::now();
    let mut next = 0;
    let mut placed = false;
    let mut alive = Instant::now();
    let mut alive_n = 0i64;
    let mut ended = false;
    loop {
        let now = Instant::now();
        if !paused {
            clock += now.duration_since(last).as_secs_f64() * 1000.0 * speed;
        }
        last = now;
        while let Ok(c) = rx.try_recv() {
            let said = match c {
                Control::Pause => {
                    paused = true;
                    tell("horizon.replay.paused", &[])
                }
                Control::Play => {
                    paused = false;
                    tell("horizon.replay.playing", &[])
                }
                Control::Speed(s) => {
                    speed = s;
                    tell("horizon.replay.speed", &[&format!("{s}")])
                }
                Control::Skip(s) => {
                    clock += s * 1000.0;
                    tell("horizon.replay.skipped", &[&format!("{s}")])
                }
            };
            writer.send(&said).map_err(e)?;
        }
        while let Some((time, body)) = recording.play.get(next) {
            if f64::from(time - start) > clock {
                break;
            }
            next += 1;
            let Some((id, _)) = varint(body) else {
                continue;
            };
            if left_out(id, body) {
                continue;
            }
            // The first position after a login or respawn places the viewer; later ones
            // would pull it back to the recorder.
            if id == ids::play::clientbound::PLAYER_POSITION {
                if placed {
                    continue;
                }
                placed = true;
                writer.send(&behind(id, body)).map_err(e)?;
                continue;
            }
            writer.send(body).map_err(e)?;
            if id == ids::play::clientbound::LOGIN || id == ids::play::clientbound::RESPAWN {
                placed = false;
                for b in spectate() {
                    writer.send(&b).map_err(e)?;
                }
                if id == ids::play::clientbound::LOGIN {
                    let seconds = format!("{}", total / 1000);
                    writer
                        .send(&tell("horizon.replay.started", &[&seconds]))
                        .map_err(e)?;
                }
            }
        }
        if next >= recording.play.len() && !ended {
            ended = true;
            writer.send(&tell("horizon.replay.ended", &[])).map_err(e)?;
        }
        // The client times out a server that sends nothing (paused, or past the end).
        if alive.elapsed() > Duration::from_secs(5) {
            alive = Instant::now();
            alive_n += 1;
            writer.send(&keep_alive(alive_n)).map_err(e)?;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(time: u32, body: &[u8]) -> Vec<u8> {
        let mut out = time.to_be_bytes().to_vec();
        out.extend_from_slice(&(body.len() as i32).to_be_bytes());
        out.extend_from_slice(body);
        out
    }

    #[test]
    fn phases_split_at_their_terminal_packets() {
        let mut data = Vec::new();
        // Login finished (2), two configuration packets, the last its finish (3), then play.
        data.extend(record(0, &[2, 0]));
        data.extend(record(1, &[1, 9]));
        data.extend(record(2, &[3]));
        data.extend(record(40, &[50, 1]));
        data.extend(record(90, &[39, 3, 0, 0, 0, 0]));
        let r = Recording::parse(&data).unwrap();
        assert_eq!(r.configuration, vec![vec![1, 9], vec![3]]);
        assert_eq!(
            r.play,
            vec![(40, vec![50, 1]), (90, vec![39, 3, 0, 0, 0, 0])]
        );
        // A game mode change is the viewer's own: left out; another game event isn't.
        assert!(left_out(39, &r.play[1].1));
        assert!(!left_out(39, &[39, 7, 0, 0, 0, 0]));
        assert!(Recording::parse(&record(0, &[2, 0])).is_err());
        // Horizon's own payloads to the recorder's window are left out, a mod's kept.
        let payload = |c: &str| [&[24u8, c.len() as u8][..], c.as_bytes(), &[1]].concat();
        assert!(left_out(24, &payload("horizon:native_sidebar")));
        assert!(!left_out(24, &payload("jade:server_ping")));
    }

    #[test]
    fn chat_lines_control_it() {
        assert!(matches!(control("pause"), Some(Control::Pause)));
        assert!(matches!(control("speed 2"), Some(Control::Speed(s)) if s == 2.0));
        assert!(matches!(control("/skip 30"), Some(Control::Skip(s)) if s == 30.0));
        assert!(control("speed").is_none());
        assert!(control("hello").is_none());
    }
}
