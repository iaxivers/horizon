//! Java mods inside Horizon: phase 1 of the Java mod bridge.
//!
//! The game starts one JVM per process, and only when `mods-java/` (beside `options.txt`)
//! holds at least one `.jar`. With no jars nothing here runs: no library is loaded and no
//! thread is made. The JVM is the player's own Java (Minecraft's runtime, `JAVA_HOME` or the
//! PATH, found the way `horizon-prepare` finds it), loaded at run time from its `libjvm`, so
//! the game links against no JDK.
//!
//! What runs in it so far: the bridge's own classes (`java/dev/horizonlab/bridge/`, compiled by
//! `build.rs` and embedded in the binary), with Rust natives registered on
//! `dev.horizonlab.bridge.Natives`. Fabric Loader and the facade come in phase 2.
//!
//! A JVM can be created once per process (JNI allows no second one after a destroy), so
//! [`start`] is idempotent: later calls return the running bridge.

use jni::objects::{JClass, JObject, JString, JValue};
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

/// The bridge's own class files, compiled by build.rs.
const NATIVES_CLASS: &[u8] = include_bytes!(concat!(
    env!("OUT_DIR"),
    "/classes/dev/horizonlab/bridge/Natives.class"
));
const BOOT_CLASS: &[u8] = include_bytes!(concat!(
    env!("OUT_DIR"),
    "/classes/dev/horizonlab/bridge/Boot.class"
));

static BRIDGE: OnceLock<Bridge> = OnceLock::new();
/// Held while a JVM is being made, so two threads never both try.
static STARTING: Mutex<()> = Mutex::new(());
/// Where `Natives.log` lines go instead of the game log (tests).
static SINK: Mutex<Option<fn(&str)>> = Mutex::new(None);

/// The running JVM.
pub struct Bridge {
    vm: JavaVM,
    java_version: String,
    home: PathBuf,
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

type CreateJavaVm =
    unsafe extern "system" fn(*mut *mut sys::JavaVM, *mut *mut c_void, *mut c_void) -> sys::jint;

/// Starts the JVM from the Java at `home`, defines the bridge's classes and calls
/// `Boot.hello(jars)`. Returns the running bridge; once one runs, every call returns it.
pub fn start(home: &Path, jars: &[PathBuf]) -> Result<&'static Bridge, String> {
    let _starting = STARTING.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(bridge) = BRIDGE.get() {
        return Ok(bridge);
    }
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

    let options: Vec<CString> = [
        // Leave SIGINT, SIGTERM and SIGHUP to the game: Ctrl+C and shutdown stay Horizon's.
        "-Xrs",
        "-Dfile.encoding=UTF-8",
        "-Dhorizon.bridge=1",
    ]
    .iter()
    .map(|o| CString::new(*o).expect("no NUL in JVM options"))
    .collect();
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
    let vm = unsafe { JavaVM::from_raw(vm) }.map_err(|e| format!("JavaVM: {e}"))?;
    let java_version = boot(&vm, jars)?;
    Ok(BRIDGE.get_or_init(|| Bridge {
        vm,
        java_version,
        home: home.to_path_buf(),
    }))
}

/// Defines the bridge's classes, registers the natives and runs `Boot.hello`.
fn boot(vm: &JavaVM, jars: &[PathBuf]) -> Result<String, String> {
    let mut env = vm
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let result = boot_in(&mut env, jars);
    result.map_err(|e| describe(&mut env, e))
}

fn boot_in(env: &mut JNIEnv, jars: &[PathBuf]) -> jni::errors::Result<String> {
    let loader = env
        .call_static_method(
            "java/lang/ClassLoader",
            "getSystemClassLoader",
            "()Ljava/lang/ClassLoader;",
            &[],
        )?
        .l()?;
    let natives = env.define_class("dev/horizonlab/bridge/Natives", &loader, NATIVES_CLASS)?;
    env.register_native_methods(
        &natives,
        &[NativeMethod {
            name: "log".into(),
            sig: "(Ljava/lang/String;)V".into(),
            fn_ptr: native_log as *mut c_void,
        }],
    )?;
    let boot = env.define_class("dev/horizonlab/bridge/Boot", &loader, BOOT_CLASS)?;
    let array = env.new_object_array(
        jars.len() as sys::jsize,
        "java/lang/String",
        JObject::null(),
    )?;
    for (i, jar) in jars.iter().enumerate() {
        let path = env.new_string(jar.to_string_lossy())?;
        env.set_object_array_element(&array, i as sys::jsize, path)?;
    }
    let version = env
        .call_static_method(
            &boot,
            "hello",
            "([Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&array)],
        )?
        .l()?;
    let version = JString::from(version);
    Ok(env.get_string(&version)?.into())
}

/// A JNI error as text; a Java exception pending is cleared and given as its `toString()`.
fn describe(env: &mut JNIEnv, error: jni::errors::Error) -> String {
    if !matches!(error, jni::errors::Error::JavaException) {
        return error.to_string();
    }
    let Ok(throwable) = env.exception_occurred() else {
        return error.to_string();
    };
    let _ = env.exception_clear();
    env.call_method(&throwable, "toString", "()Ljava/lang/String;", &[])
        .and_then(|v| v.l())
        .and_then(|s| env.get_string(&JString::from(s)).map(String::from))
        .unwrap_or_else(|_| "a Java exception (its toString() failed)".into())
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
