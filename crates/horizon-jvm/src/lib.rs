//! Java mods inside Horizon: the Java mod bridge.
//!
//! The game starts one JVM per process, and only when `mods-java/` (beside `options.txt`)
//! holds at least one `.jar`. With no jars nothing here runs: no library is loaded and no
//! thread is made. The JVM is the player's own Java (Minecraft's runtime, `JAVA_HOME` or the
//! PATH, found the way `horizon-prepare` finds it), loaded at run time from its `libjvm`, so
//! the game links against no JDK.
//!
//! What runs in it:
//! - the bridge's own jar (`java/dev/horizonlab/bridge/`, built by `build.rs`, embedded in the
//!   binary and written to the work folder), with Rust natives on `dev.horizonlab.bridge.Natives`;
//! - with a game jar (the player's own, [`facade::game_jar`]), Fabric Loader ([`libs`])
//!   through `HorizonGameProvider`: it finds the mods in `mods-java/` and Fabric API, applies
//!   their Mixins to the game's classes, bootstraps the game's registries and runs the mods'
//!   `main` and `client`/`server` entrypoints.
//!
//! A JVM can be created once per process (JNI allows no second one after a destroy), so
//! [`start`] is idempotent: later calls return the running bridge.

pub mod content;
pub mod events;
pub mod facade;
pub mod libs;
pub mod world;

use jni::objects::{JClass, JObject, JObjectArray, JString, JValue};
use jni::{JNIEnv, JavaVM, NativeMethod, sys};
use std::ffi::{CString, c_void};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

/// The folder of Java mods, beside `options.txt`.
pub const DIR: &str = "mods-java";

/// The Java version mods for Minecraft 26.3 are built for (`research-lock.json`'s `java_major`).
pub const JAVA_MAJOR: i64 = 25;

/// Overrides the Java used, whatever its version (a developer's JDK). Its value is a Java home:
/// the folder holding `bin/` and `lib/`.
pub const JAVA_HOME_VAR: &str = "HORIZON_JAVA_HOME";

/// The bridge's jar, built by build.rs.
const BRIDGE_JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/horizon-bridge.jar"));
/// The platform mod (`java/platform/`, Fabric mod `horizon`), built by build.rs against the
/// prepared game jar and Fabric API; empty when the checkout had no prepared game to build it
/// against.
const PLATFORM_JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/horizon-platform.jar"));

static BRIDGE: OnceLock<Bridge> = OnceLock::new();
/// Held while a JVM is being made, so two threads never both try.
static STARTING: Mutex<()> = Mutex::new(());
/// Where `Natives.log` lines go instead of the game log (tests).
static SINK: Mutex<Option<fn(&str)>> = Mutex::new(None);

/// Which half of the game the mods run in: their `client` or `server` entrypoints.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Side {
    Client,
    Server,
}

/// What [`start`] runs.
pub struct Config {
    /// The Java home to load the JVM from ([`find_java_home`]).
    pub home: PathBuf,
    /// The bridge's own files: its jar, and Fabric Loader's in `lib/`.
    pub work: PathBuf,
    /// The jars in `mods-java/` ([`jars`]).
    pub jars: Vec<PathBuf>,
    /// More class path entries (tests put a facade there to load it without Fabric).
    pub class_path: Vec<PathBuf>,
    /// Fabric Loader runs only with a game jar.
    pub fabric: Option<Fabric>,
}

/// Fabric Loader's part of [`Config`].
pub struct Fabric {
    /// The game jar ([`facade::game_jar`], or a facade, [`facade::ensure`]).
    pub game_jar: PathBuf,
    /// The game's libraries ([`facade::game_libraries`]), on Fabric's class path beside it.
    pub libraries: Vec<PathBuf>,
    /// Mod jars to load besides the folder's: Fabric API ([`libs::ensure_fabric_api`]).
    pub add_mods: Vec<PathBuf>,
    /// Loads the platform mod (game events for mods, [`events`]) when this build has it. Off
    /// for a game jar that isn't Minecraft (the bridge's own tests).
    pub platform: bool,
    /// The folder Fabric reads mods from (`mods-java/`).
    pub mods: PathBuf,
    /// The game folder: Fabric writes `config/` there.
    pub game_dir: PathBuf,
    /// The game version Horizon plays.
    pub version: String,
    pub side: Side,
}

/// The running JVM.
pub struct Bridge {
    vm: JavaVM,
    java_version: String,
    home: PathBuf,
    /// The mods Fabric loaded, `id version`, built-in ones included; empty without Fabric.
    mods: Vec<String>,
    fabric: bool,
}

impl Bridge {
    /// The JVM, for attaching other threads.
    pub fn vm(&self) -> &JavaVM {
        &self.vm
    }
    /// `Runtime.version()` of the running JVM, as Java reported it.
    pub fn java_version(&self) -> &str {
        &self.java_version
    }
    /// The Java home the JVM was loaded from.
    pub fn home(&self) -> &Path {
        &self.home
    }
    /// Whether Fabric Loader started.
    pub fn fabric(&self) -> bool {
        self.fabric
    }
    /// The mods Fabric loaded, as `id version`: `minecraft`, `java` and `fabricloader` too.
    pub fn mods(&self) -> &[String] {
        &self.mods
    }
    /// Loads (and initializes) a class by its binary name, `net/minecraft/...`: through
    /// Fabric's class loader when Fabric runs (so Mixins apply), else the system one.
    pub fn check_class(&self, name: &str) -> Result<(), String> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|e| format!("attach: {e}"))?;
        let found = self.find_class(&mut env, name);
        match found {
            Ok(class) => {
                let _ = env.delete_local_ref(class);
                Ok(())
            }
            Err(e) => Err(describe(&mut env, e)),
        }
    }
    /// A game or mod class, through Fabric's class loader when Fabric runs.
    pub fn find_class<'local>(
        &self,
        env: &mut JNIEnv<'local>,
        name: &str,
    ) -> jni::errors::Result<JClass<'local>> {
        if !self.fabric {
            return env.find_class(name);
        }
        let name = env.new_string(name)?;
        let class = env
            .call_static_method(
                "dev/horizonlab/bridge/fabric/FabricBoot",
                "gameClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                &[JValue::Object(&name)],
            )?
            .l()?;
        Ok(JClass::from(class))
    }
}

/// The running bridge, if [`start`] succeeded.
pub fn get() -> Option<&'static Bridge> {
    BRIDGE.get()
}

/// Sends `Natives.log` lines to `sink` instead of the game log.
pub fn set_log_sink(sink: Option<fn(&str)>) {
    *SINK.lock().unwrap_or_else(|e| e.into_inner()) = sink;
}

/// The `.jar` files in `dir`, sorted by name, as absolute paths. A missing folder has none.
pub fn jars(dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut jars: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_file() && p.extension().is_some_and(|e| e.eq_ignore_ascii_case("jar")))
        .map(|p| std::fs::canonicalize(&p).unwrap_or(p))
        .collect();
    jars.sort();
    jars
}

/// The Java home to run mods with: `HORIZON_JAVA_HOME`, else a Java [`JAVA_MAJOR`] or newer
/// from `JAVA_HOME`, the PATH or Minecraft's own runtimes (`horizon_prepare::java`).
pub fn find_java_home() -> Result<PathBuf, String> {
    if let Some(home) = std::env::var_os(JAVA_HOME_VAR).filter(|h| !h.is_empty()) {
        return Ok(PathBuf::from(home));
    }
    let minecraft = horizon_prepare::artifacts::minecraft_dir(None);
    let runtimes = horizon_prepare::java::minecraft_runtimes(&minecraft.join("runtime"));
    let jdk = horizon_prepare::java::find_runtime(JAVA_MAJOR, &runtimes)?;
    let java = std::fs::canonicalize(&jdk.java).unwrap_or(jdk.java);
    java.parent()
        .and_then(Path::parent)
        .map(Path::to_path_buf)
        .ok_or_else(|| format!("{} is not inside a Java home", java.display()))
}

/// The JVM library inside a Java home.
pub fn libjvm(home: &Path) -> Result<PathBuf, String> {
    let candidates = [
        "lib/server/libjvm.so",
        "lib/server/libjvm.dylib",
        "bin/server/jvm.dll",
        // Java 8 layouts, so the error below names the real problem (too old) less often.
        "jre/lib/amd64/server/libjvm.so",
        "jre/lib/server/libjvm.dylib",
    ];
    candidates
        .iter()
        .map(|c| home.join(c))
        .find(|p| p.is_file())
        .ok_or_else(|| {
            format!(
                "no JVM library in {} (looked for lib/server/libjvm.so, .dylib, bin/server/jvm.dll)",
                home.display()
            )
        })
}

/// Writes an embedded jar to `work/name` when it differs.
/// A folder of `horizon-bridge.jar` and `horizon-platform.jar` to run in place of the built-in
/// ones (bridge development; see docs/java-bridge.md).
pub const DEV_JARS: &str = "HORIZON_JAVA_JARS";

fn write_jar(work: &Path, name: &str, bytes: &[u8]) -> Result<PathBuf, String> {
    // Developing the bridge: jars rebuilt on their own (tools/java_bridge/java.sh) are used in
    // place of the ones built into the game, so a Java change needs no game rebuild.
    if let Some(dir) = std::env::var_os(DEV_JARS) {
        let path = Path::new(&dir).join(name);
        if path.is_file() {
            return Ok(path);
        }
    }
    std::fs::create_dir_all(work).map_err(|e| format!("{}: {e}", work.display()))?;
    let path = work.join(name);
    if std::fs::read(&path).ok().as_deref() != Some(bytes) {
        std::fs::write(&path, bytes).map_err(|e| format!("{}: {e}", path.display()))?;
    }
    Ok(path)
}

/// Whether this build carries the platform mod (game events for mods).
pub fn has_platform() -> bool {
    !PLATFORM_JAR.is_empty()
}

type CreateJavaVm =
    unsafe extern "system" fn(*mut *mut sys::JavaVM, *mut *mut c_void, *mut c_void) -> sys::jint;

/// Starts the JVM, registers the natives, says hello and, with [`Config::fabric`], starts
/// Fabric Loader. Returns the running bridge; once one runs, every call returns it.
pub fn start(config: &Config) -> Result<&'static Bridge, String> {
    let _starting = STARTING.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(bridge) = BRIDGE.get() {
        return Ok(bridge);
    }
    let mut class_path = vec![write_jar(&config.work, "horizon-bridge.jar", BRIDGE_JAR)?];
    if config.fabric.is_some() {
        class_path.extend(libs::ensure(&config.work.join("lib"))?);
    }
    class_path.extend(config.class_path.iter().cloned());
    let vm = create_vm(&config.home, &class_path)?;
    let mut env = vm
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let java_version = boot(&mut env, &config.jars).map_err(|e| describe(&mut env, e))?;
    let mods = match &config.fabric {
        Some(fabric) => {
            let mut add_mods = fabric.add_mods.clone();
            let platform = fabric.platform && has_platform();
            if platform {
                add_mods.push(write_jar(
                    &config.work,
                    "horizon-platform.jar",
                    PLATFORM_JAR,
                )?);
            }
            let mods =
                start_fabric(&mut env, fabric, &add_mods).map_err(|e| describe(&mut env, e))?;
            if platform {
                prepare_data(&mut env, &config.work.join("datapacks"))
                    .map_err(|e| describe(&mut env, e))?;
            }
            mods
        }
        None => Vec::new(),
    };
    Ok(BRIDGE.get_or_init(|| Bridge {
        vm,
        java_version,
        home: config.home.clone(),
        mods,
        fabric: config.fabric.is_some(),
    }))
}

fn create_vm(home: &Path, class_path: &[PathBuf]) -> Result<JavaVM, String> {
    let path = libjvm(home)?;
    // SAFETY: loading libjvm runs its initialisers, which only set up the library itself.
    let library = unsafe { libloading::Library::new(&path) }
        .map_err(|e| format!("could not load {}: {e}", path.display()))?;
    // SAFETY: JNI_CreateJavaVM has this signature in every JVM (jni.h).
    let create: CreateJavaVm = unsafe {
        *library
            .get::<CreateJavaVm>(b"JNI_CreateJavaVM\0")
            .map_err(|e| format!("{} has no JNI_CreateJavaVM: {e}", path.display()))?
    };
    // A JVM never unloads: keep its library mapped for the life of the process.
    std::mem::forget(library);

    let joined = std::env::join_paths(class_path).map_err(|e| format!("class path: {e}"))?;
    let options = [
        // Leave SIGINT, SIGTERM and SIGHUP to the game: Ctrl+C and shutdown stay Horizon's.
        "-Xrs".to_string(),
        "-Dfile.encoding=UTF-8".into(),
        "-Dhorizon.bridge=1".into(),
        format!("-Djava.class.path={}", joined.to_string_lossy()),
    ];
    let options: Vec<CString> = options
        .into_iter()
        .map(|o| CString::new(o).map_err(|_| "a NUL in a JVM option".to_string()))
        .collect::<Result<_, _>>()?;
    let mut raw: Vec<sys::JavaVMOption> = options
        .iter()
        .map(|o| sys::JavaVMOption {
            optionString: o.as_ptr() as *mut _,
            extraInfo: std::ptr::null_mut(),
        })
        .collect();
    let mut args = sys::JavaVMInitArgs {
        version: sys::JNI_VERSION_1_8,
        nOptions: raw.len() as sys::jint,
        options: raw.as_mut_ptr(),
        ignoreUnrecognized: sys::JNI_FALSE,
    };
    let mut vm: *mut sys::JavaVM = std::ptr::null_mut();
    let mut env: *mut c_void = std::ptr::null_mut();
    // SAFETY: the pointers are valid for the call; `options` outlives it.
    let code = unsafe { create(&mut vm, &mut env, &mut args as *mut _ as *mut c_void) };
    if code != sys::JNI_OK || vm.is_null() {
        return Err(format!(
            "JNI_CreateJavaVM failed ({code}) with the Java at {}",
            home.display()
        ));
    }
    // SAFETY: `vm` is the JVM just created.
    unsafe { JavaVM::from_raw(vm) }.map_err(|e| format!("JavaVM: {e}"))
}

fn strings<'local>(
    env: &mut JNIEnv<'local>,
    items: &[String],
) -> jni::errors::Result<JObjectArray<'local>> {
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
    Ok(array)
}

/// Registers the natives and runs `Boot.hello`.
fn boot(env: &mut JNIEnv, jars: &[PathBuf]) -> jni::errors::Result<String> {
    let natives = env.find_class("dev/horizonlab/bridge/Natives")?;
    env.register_native_methods(
        &natives,
        &[
            NativeMethod {
                name: "log".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: native_log as *mut c_void,
            },
            NativeMethod {
                name: "tell".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_tell as *mut c_void,
            },
            NativeMethod {
                name: "overlay".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_overlay as *mut c_void,
            },
            NativeMethod {
                name: "teleport".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;DDDFF)V".into(),
                fn_ptr: native_teleport as *mut c_void,
            },
            NativeMethod {
                name: "abilities".into(),
                sig: "(Ljava/lang/String;IIFF)V".into(),
                fn_ptr: native_abilities as *mut c_void,
            },
            NativeMethod {
                name: "tabName".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_tab_name as *mut c_void,
            },
            NativeMethod {
                name: "openMenu".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_open_menu as *mut c_void,
            },
            NativeMethod {
                name: "screenFrame".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_screen_frame as *mut c_void,
            },
            NativeMethod {
                name: "hudFrame".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_hud_frame as *mut c_void,
            },
            NativeMethod {
                name: "clientScreen".into(),
                sig: "(Ljava/lang/String;Z)V".into(),
                fn_ptr: native_client_screen as *mut c_void,
            },
            NativeMethod {
                name: "blockEntity".into(),
                sig: "(Ljava/lang/String;III)Ljava/lang/String;".into(),
                fn_ptr: native_block_entity as *mut c_void,
            },
            NativeMethod {
                name: "setBlockEntity".into(),
                sig: "(Ljava/lang/String;IIILjava/lang/String;)V".into(),
                fn_ptr: native_set_block_entity as *mut c_void,
            },
            NativeMethod {
                name: "light".into(),
                sig: "(Ljava/lang/String;III)I".into(),
                fn_ptr: native_light as *mut c_void,
            },
            NativeMethod {
                name: "setFood".into(),
                sig: "(Ljava/lang/String;IF)V".into(),
                fn_ptr: native_set_food as *mut c_void,
            },
            NativeMethod {
                name: "menuContent".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)V"
                    .into(),
                fn_ptr: native_menu_content as *mut c_void,
            },
            NativeMethod {
                name: "blockState".into(),
                sig: "(Ljava/lang/String;III)Ljava/lang/String;".into(),
                fn_ptr: native_block_state as *mut c_void,
            },
            NativeMethod {
                name: "chunkBlocks".into(),
                sig: "(Ljava/lang/String;III[I)[Ljava/lang/String;".into(),
                fn_ptr: native_chunk_blocks as *mut c_void,
            },
            NativeMethod {
                name: "chunkBiomes".into(),
                sig: "(Ljava/lang/String;III[I)[Ljava/lang/String;".into(),
                fn_ptr: native_chunk_biomes as *mut c_void,
            },
            NativeMethod {
                name: "viewEpoch".into(),
                sig: "()J".into(),
                fn_ptr: native_view_epoch as *mut c_void,
            },
            NativeMethod {
                name: "setBlock".into(),
                sig: "(Ljava/lang/String;IIILjava/lang/String;)V".into(),
                fn_ptr: native_set_block as *mut c_void,
            },
            NativeMethod {
                name: "dropItem".into(),
                sig: "(Ljava/lang/String;DDDDDDLjava/lang/String;I)V".into(),
                fn_ptr: native_drop_item as *mut c_void,
            },
            NativeMethod {
                name: "levelEvent".into(),
                sig: "(Ljava/lang/String;IIIIILjava/lang/String;)V".into(),
                fn_ptr: native_level_event as *mut c_void,
            },
            NativeMethod {
                name: "setSlot".into(),
                sig: "(Ljava/lang/String;ILjava/lang/String;)V".into(),
                fn_ptr: native_set_slot as *mut c_void,
            },
            NativeMethod {
                name: "packet".into(),
                sig: "(Ljava/lang/String;[B)V".into(),
                fn_ptr: native_packet as *mut c_void,
            },
            NativeMethod {
                name: "broadcast".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: native_broadcast as *mut c_void,
            },
            NativeMethod {
                name: "chunkTickets".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_chunk_tickets as *mut c_void,
            },
            NativeMethod {
                name: "allocate".into(),
                sig: "(Ljava/lang/Class;)Ljava/lang/Object;".into(),
                fn_ptr: native_allocate as *mut c_void,
            },
        ],
    )?;
    let jars: Vec<String> = jars
        .iter()
        .map(|j| j.to_string_lossy().into_owned())
        .collect();
    let array = strings(env, &jars)?;
    let version = env
        .call_static_method(
            "dev/horizonlab/bridge/Boot",
            "hello",
            "([Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&array)],
        )?
        .l()?;
    let version = JString::from(version);
    Ok(env.get_string(&version)?.into())
}

/// Runs `FabricBoot.start`: Knot, mod discovery, Mixin, entrypoints.
fn start_fabric(
    env: &mut JNIEnv,
    fabric: &Fabric,
    add_mods: &[PathBuf],
) -> jni::errors::Result<Vec<String>> {
    let side = env.new_string(match fabric.side {
        Side::Client => "client",
        Side::Server => "server",
    })?;
    let version = env.new_string(&fabric.version)?;
    let game_jar = env.new_string(fabric.game_jar.to_string_lossy())?;
    let mods = env.new_string(fabric.mods.to_string_lossy())?;
    let game_dir = env.new_string(fabric.game_dir.to_string_lossy())?;
    let libraries: Vec<String> = fabric
        .libraries
        .iter()
        .map(|l| l.to_string_lossy().into_owned())
        .collect();
    let libraries = strings(env, &libraries)?;
    let add_mods: Vec<String> = add_mods
        .iter()
        .map(|m| m.to_string_lossy().into_owned())
        .collect();
    let add_mods = strings(env, &add_mods)?;
    let loaded = env
        .call_static_method(
            "dev/horizonlab/bridge/fabric/FabricBoot",
            "start",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",
            &[
                JValue::Object(&side),
                JValue::Object(&version),
                JValue::Object(&game_jar),
                JValue::Object(&libraries),
                JValue::Object(&mods),
                JValue::Object(&add_mods),
                JValue::Object(&game_dir),
            ],
        )?
        .l()?;
    let loaded = JObjectArray::from(loaded);
    let count = env.get_array_length(&loaded)?;
    let mut out = Vec::with_capacity(count as usize);
    for i in 0..count {
        let item = JString::from(env.get_object_array_element(&loaded, i)?);
        out.push(env.get_string(&item)?.into());
        env.delete_local_ref(item)?;
    }
    Ok(out)
}

/// Loads the server's data (vanilla's data pack and the mods'): `HorizonData.prepare`, in the
/// platform mod, through Fabric's class loader.
fn prepare_data(env: &mut JNIEnv, datapacks: &Path) -> jni::errors::Result<()> {
    let name = env.new_string("dev.horizonlab.platform.HorizonData")?;
    let class = env
        .call_static_method(
            "dev/horizonlab/bridge/fabric/FabricBoot",
            "gameClass",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&name)],
        )?
        .l()?;
    let datapacks = env.new_string(datapacks.to_string_lossy())?;
    env.call_static_method(
        JClass::from(class),
        "prepare",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&datapacks)],
    )?;
    Ok(())
}

/// A JNI error as text; a Java exception pending is cleared and given as its `toString()`,
/// with its cause's.
pub(crate) fn describe(env: &mut JNIEnv, error: jni::errors::Error) -> String {
    if !matches!(error, jni::errors::Error::JavaException) {
        return error.to_string();
    }
    let Ok(throwable) = env.exception_occurred() else {
        return error.to_string();
    };
    let _ = env.exception_clear();
    let mut text = Vec::new();
    let mut current: JObject = throwable.into();
    for _ in 0..8 {
        if current.is_null() {
            break;
        }
        let line = env
            .call_method(&current, "toString", "()Ljava/lang/String;", &[])
            .and_then(|v| v.l())
            .and_then(|s| env.get_string(&JString::from(s)).map(String::from))
            .unwrap_or_else(|_| "a Java exception (its toString() failed)".into());
        text.push(line);
        current = match env
            .call_method(&current, "getCause", "()Ljava/lang/Throwable;", &[])
            .and_then(|v| v.l())
        {
            Ok(cause) => cause,
            Err(_) => break,
        };
    }
    let _ = env.exception_clear();
    text.join(", caused by ")
}

fn emit(line: &str) {
    match *SINK.lock().unwrap_or_else(|e| e.into_inner()) {
        Some(sink) => sink(line),
        None => log::info!("[java] {line}"),
    }
}

/// `dev.horizonlab.bridge.Natives.log(String)`.
extern "system" fn native_log(mut env: JNIEnv, _class: JClass, text: JString) {
    // A panic must not unwind into the JVM.
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(text) = env.get_string(&text) {
            emit(&String::from(text));
        }
    }));
}

/// `Natives.overlay(String uuid, String text)`.
extern "system" fn native_overlay(mut env: JNIEnv, _class: JClass, uuid: JString, text: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(text)) = (env.get_string(&uuid), env.get_string(&text)) else {
            return;
        };
        let (uuid, text) = (String::from(uuid), String::from(text));
        events::push(events::Action::Overlay { uuid, text });
    }));
}

/// `Natives.tell(String uuid, String text)`.
extern "system" fn native_tell(mut env: JNIEnv, _class: JClass, uuid: JString, text: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(text)) = (env.get_string(&uuid), env.get_string(&text)) else {
            return;
        };
        let (uuid, text) = (String::from(uuid), String::from(text));
        events::push(events::Action::Tell { uuid, text });
    }));
}

/// `Natives.teleport(String uuid, String dimension, double x, y, z, float yaw, pitch)`.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_teleport(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    dimension: JString,
    x: sys::jdouble,
    y: sys::jdouble,
    z: sys::jdouble,
    yaw: sys::jfloat,
    pitch: sys::jfloat,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(dimension)) = (env.get_string(&uuid), env.get_string(&dimension)) else {
            return;
        };
        events::push(events::Action::Teleport {
            uuid: uuid.into(),
            dimension: dimension.into(),
            position: [x, y, z],
            yaw,
            pitch,
        });
    }));
}

/// `Natives.abilities(String uuid, int gameMode, int flags, float flyingSpeed, float
/// walkingSpeed)`.
extern "system" fn native_abilities(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    game_mode: sys::jint,
    flags: sys::jint,
    flying_speed: sys::jfloat,
    walking_speed: sys::jfloat,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(uuid) = env.get_string(&uuid) {
            events::push(events::Action::Abilities {
                uuid: uuid.into(),
                game_mode,
                flags: flags as i8,
                flying_speed,
                walking_speed,
            });
        }
    }));
}

/// `Natives.tabName(String uuid, String json)`: json is null for the player's own name.
extern "system" fn native_tab_name(mut env: JNIEnv, _class: JClass, uuid: JString, json: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid).map(String::from) else {
            return;
        };
        let json = if json.is_null() {
            None
        } else {
            env.get_string(&json).ok().map(String::from)
        };
        events::push(events::Action::TabName { uuid, json });
    }));
}

/// `Natives.chunkTickets(String dimension, String chunks)`: "x,z;x,z;...".
extern "system" fn native_chunk_tickets(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    chunks: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(chunks)) = (env.get_string(&dimension), env.get_string(&chunks))
        else {
            return;
        };
        let chunks = parse_chunks(&String::from(chunks));
        events::push(events::Action::ChunkTickets {
            dimension: String::from(dimension),
            chunks,
        });
    }));
}

/// "x,z;x,z;..." as chunk positions (malformed entries skipped).
fn parse_chunks(text: &str) -> Vec<(i32, i32)> {
    text.split(';')
        .filter_map(|pair| {
            let (x, z) = pair.split_once(',')?;
            Some((x.trim().parse().ok()?, z.trim().parse().ok()?))
        })
        .collect()
}

/// `Natives.openMenu(String uuid, String menu, String title)`: title may be null.
extern "system" fn native_open_menu(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    menu: JString,
    title: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(menu)) = (env.get_string(&uuid), env.get_string(&menu)) else {
            return;
        };
        let (uuid, menu) = (String::from(uuid), String::from(menu));
        let title = if title.is_null() {
            None
        } else {
            env.get_string(&title).ok().map(String::from)
        };
        events::push(events::Action::OpenMenu { uuid, menu, title });
    }));
}

/// `Natives.screenFrame(String uuid, String frame)`.
extern "system" fn native_screen_frame(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    frame: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(frame)) = (env.get_string(&uuid), env.get_string(&frame)) else {
            return;
        };
        events::push(events::Action::ScreenFrame {
            uuid: uuid.into(),
            frame: frame.into(),
        });
    }));
}

/// `Natives.hudFrame(String uuid, String frame)`.
extern "system" fn native_hud_frame(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    frame: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(frame)) = (env.get_string(&uuid), env.get_string(&frame)) else {
            return;
        };
        events::push(events::Action::HudFrame {
            uuid: uuid.into(),
            frame: frame.into(),
        });
    }));
}

/// `Natives.clientScreen(String uuid, boolean open)`.
extern "system" fn native_client_screen(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    open: jni::sys::jboolean,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid) else {
            return;
        };
        events::push(events::Action::ClientScreen {
            uuid: uuid.into(),
            open: open != 0,
        });
    }));
}

/// `Natives.blockEntity(String dimension, int x, int y, int z)`: SNBT, or null.
extern "system" fn native_block_entity<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    x: jni::sys::jint,
    y: jni::sys::jint,
    z: jni::sys::jint,
) -> JString<'local> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return JString::from(JObject::null());
        };
        match world::block_entity(&dimension, x, y, z) {
            Some(snbt) => env
                .new_string(snbt)
                .unwrap_or_else(|_| JString::from(JObject::null())),
            None => JString::from(JObject::null()),
        }
    }))
    .unwrap_or_else(|_| JString::from(JObject::null()))
}

/// `Natives.setBlockEntity(String dimension, int x, int y, int z, String snbt)`.
extern "system" fn native_set_block_entity(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: jni::sys::jint,
    y: jni::sys::jint,
    z: jni::sys::jint,
    snbt: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(snbt)) = (env.get_string(&dimension), env.get_string(&snbt)) else {
            return;
        };
        events::push(events::Action::SetBlockEntity {
            dimension: dimension.into(),
            position: [x, y, z],
            snbt: snbt.into(),
        });
    }));
}

/// `Natives.light(String dimension, int x, int y, int z)`: -1 where no chunk is loaded.
extern "system" fn native_light(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: jni::sys::jint,
    y: jni::sys::jint,
    z: jni::sys::jint,
) -> jni::sys::jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return -1;
        };
        world::light(&dimension, x, y, z).map_or(-1, i32::from)
    }))
    .unwrap_or(-1)
}

/// `Natives.setFood(String uuid, int food, float saturation)`.
extern "system" fn native_set_food(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    food: jni::sys::jint,
    saturation: jni::sys::jfloat,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(uuid) = env.get_string(&uuid).map(String::from) {
            events::push(events::Action::Food {
                uuid,
                food,
                saturation,
            });
        }
    }));
}

/// `Natives.menuContent(String uuid, String menu, String title, String[] items, String carried)`.
extern "system" fn native_menu_content(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    menu: JString,
    title: JString,
    items: JObjectArray,
    carried: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid).map(String::from) else {
            return;
        };
        let mut string = |s: &JString| -> Option<String> {
            (!s.is_null())
                .then(|| env.get_string(s).ok().map(String::from))
                .flatten()
        };
        let open = string(&menu).map(|m| (m, string(&title)));
        let carried = string(&carried);
        let items = if items.is_null() {
            None
        } else {
            let n = env.get_array_length(&items).unwrap_or(0);
            let mut out = Vec::with_capacity(n as usize);
            for i in 0..n {
                let item = env
                    .get_object_array_element(&items, i)
                    .ok()
                    .filter(|o| !o.is_null())
                    .and_then(|o| env.get_string(&JString::from(o)).ok().map(String::from));
                out.push(item);
            }
            Some(out)
        };
        events::push(events::Action::MenuContent {
            uuid,
            open,
            items,
            carried,
        });
    }));
}

/// `Natives.blockState(String dimension, int x, int y, int z)`: Horizon's block there, or
/// null where no chunk is loaded (or outside a call that lent the world).
extern "system" fn native_block_state<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    x: sys::jint,
    y: sys::jint,
    z: sys::jint,
) -> sys::jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return std::ptr::null_mut();
        };
        match world::block(&dimension, x, y, z) {
            Some(state) => env
                .new_string(state)
                .map(JString::into_raw)
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `Natives.chunkBlocks(String dimension, int cx, int cz, int minY, int[] out)`: a loaded
/// chunk's blocks at once (`world::Blocks::column`), or null.
extern "system" fn native_chunk_blocks<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    cx: sys::jint,
    cz: sys::jint,
    min_y: sys::jint,
    out: jni::objects::JIntArray<'local>,
) -> sys::jobjectArray {
    chunk_palette(env, dimension, cx, cz, min_y, out, world::column)
}

/// `Natives.chunkBiomes(String dimension, int cx, int cz, int minY, int[] out)`: a loaded
/// chunk's biomes at once (`world::Blocks::column_biomes`), or null.
extern "system" fn native_chunk_biomes<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    cx: sys::jint,
    cz: sys::jint,
    min_y: sys::jint,
    out: jni::objects::JIntArray<'local>,
) -> sys::jobjectArray {
    chunk_palette(env, dimension, cx, cz, min_y, out, world::column_biomes)
}

type ColumnRead = fn(&str, i32, i32, i32, &mut [i32]) -> Option<Vec<String>>;

fn chunk_palette<'local>(
    mut env: JNIEnv<'local>,
    dimension: JString<'local>,
    cx: sys::jint,
    cz: sys::jint,
    min_y: sys::jint,
    out: jni::objects::JIntArray<'local>,
    read: ColumnRead,
) -> sys::jobjectArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return std::ptr::null_mut();
        };
        let Ok(len) = env.get_array_length(&out) else {
            return std::ptr::null_mut();
        };
        let mut indices = vec![0i32; len as usize];
        let Some(palette) = read(&dimension, cx, cz, min_y, &mut indices) else {
            return std::ptr::null_mut();
        };
        if env.set_int_array_region(&out, 0, &indices).is_err() {
            return std::ptr::null_mut();
        }
        let Ok(array) = env.new_object_array(
            palette.len() as sys::jsize,
            "java/lang/String",
            JObject::null(),
        ) else {
            return std::ptr::null_mut();
        };
        for (i, name) in palette.iter().enumerate() {
            let Ok(s) = env.new_string(name) else {
                return std::ptr::null_mut();
            };
            let _ = env.set_object_array_element(&array, i as sys::jsize, &s);
            let _ = env.delete_local_ref(s);
        }
        array.into_raw()
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `Natives.viewEpoch()`: the number of the world view lent last (`world::epoch`).
extern "system" fn native_view_epoch(_env: JNIEnv, _class: JClass) -> sys::jlong {
    world::epoch()
}

/// `Natives.setBlock(String dimension, int x, int y, int z, String state)`.
extern "system" fn native_set_block(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: sys::jint,
    y: sys::jint,
    z: sys::jint,
    state: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(state)) = (env.get_string(&dimension), env.get_string(&state))
        else {
            return;
        };
        events::push(events::Action::SetBlock {
            dimension: dimension.into(),
            position: [x, y, z],
            state: state.into(),
        });
    }));
}

/// `Natives.dropItem(String dimension, double x, y, z, double vx, vy, vz, String item, int count)`.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_drop_item(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: sys::jdouble,
    y: sys::jdouble,
    z: sys::jdouble,
    vx: sys::jdouble,
    vy: sys::jdouble,
    vz: sys::jdouble,
    item: JString,
    count: sys::jint,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(item)) = (env.get_string(&dimension), env.get_string(&item)) else {
            return;
        };
        events::push(events::Action::DropItem {
            dimension: dimension.into(),
            position: [x, y, z],
            velocity: [vx, vy, vz],
            item: item.into(),
            count,
        });
    }));
}

/// `Natives.levelEvent(String dimension, int type, int x, int y, int z, int data, String state)`.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_level_event(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    id: sys::jint,
    x: sys::jint,
    y: sys::jint,
    z: sys::jint,
    data: sys::jint,
    state: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return;
        };
        let state = if state.is_null() {
            None
        } else {
            env.get_string(&state).ok().map(String::from)
        };
        events::push(events::Action::LevelEvent {
            dimension,
            id,
            position: [x, y, z],
            data,
            state,
        });
    }));
}

/// `Natives.setSlot(String uuid, int slot, String item)`: item is SNBT, or null for empty.
extern "system" fn native_set_slot(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    slot: sys::jint,
    item: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid).map(String::from) else {
            return;
        };
        let item = if item.is_null() {
            None
        } else {
            env.get_string(&item).ok().map(String::from)
        };
        if let Ok(slot) = usize::try_from(slot) {
            events::push(events::Action::SetSlot { uuid, slot, item });
        }
    }));
}

/// `Natives.packet(String uuid, byte[] data)`.
extern "system" fn native_packet(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    data: jni::objects::JByteArray,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid).map(String::from) else {
            return;
        };
        if let Ok(data) = env.convert_byte_array(&data) {
            events::push(events::Action::Packet { uuid, data });
        }
    }));
}

/// `Natives.broadcast(String text)`.
extern "system" fn native_broadcast(mut env: JNIEnv, _class: JClass, text: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(text) = env.get_string(&text) {
            events::push(events::Action::Broadcast { text: text.into() });
        }
    }));
}

/// `Natives.allocate(Class)`: an instance made without running a constructor (JNI
/// `AllocObject`), for the game objects the platform mod mirrors Horizon's state into.
extern "system" fn native_allocate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    of: JClass<'local>,
) -> sys::jobject {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        env.alloc_object(&of)
            .map(JObject::into_raw)
            .unwrap_or(std::ptr::null_mut())
    }))
    .unwrap_or(std::ptr::null_mut())
}
