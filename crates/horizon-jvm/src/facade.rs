//! The facade: Minecraft's classes with their bodies taken out.
//!
//! Fabric mods are compiled against `net.minecraft.*`, and Fabric Loader and Mixin need a game
//! jar to load and patch. Horizon has no Java game, so the bridge makes one from the player's
//! own 26.3 jar, on the player's machine: every class keeps its name, superclass, interfaces,
//! fields, method names and descriptors, annotations and constant pool, and every method body
//! is replaced by a stub. Nothing of the facade is ever committed or shipped.
//!
//! Stubs, for now (phase 2):
//! - a method returns its type's default (`0`, `false`, `null`, nothing);
//! - a constructor calls the superclass (or `this`) constructor the original called, with
//!   default arguments, then returns, so the class still verifies;
//! - a static initializer does nothing.
//!
//! Later phases route the stubs to Rust natives. The bridge makes facade objects with JNI's
//! `AllocObject`, which runs no constructor, so constructor stubs only run when a mod itself
//! writes `new` on a game class.
//!
//! Straight-line stubs need no `StackMapTable`. The constant pool is copied as it is, with the
//! class's own attributes (`BootstrapMethods`, `InnerClasses`, `NestHost`, `Record`...), so their
//! indices stay valid.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

/// Bumped whenever the stubs change, so cached facades are rebuilt.
pub const FORMAT: u32 = 1;

/// The facade of this checkout's prepared game jars (`.local/minecraft/<version>/jars/`, made
/// by `horizon-prepare --prepare` from the player's own copy of the game), built once and kept
/// beside them in `bridge/facade.jar`. `root` is the folder holding `research-lock.json`.
/// Rebuilt when the jars or [`FORMAT`] change.
pub fn ensure(root: &Path) -> Result<PathBuf, String> {
    let lock = horizon_prepare::artifacts::Lock::read(root)?;
    let jars = lock.directory.join("jars");
    let inputs: Vec<PathBuf> = ["client.jar", "server.jar"]
        .iter()
        .map(|j| jars.join(j))
        .filter(|j| j.is_file())
        .collect();
    if inputs.is_empty() {
        return Err(format!(
            "no game jars in {}: run horizon-prepare --prepare first",
            jars.display()
        ));
    }
    let out = lock.directory.join("bridge/facade.jar");
    let stamp_path = lock.directory.join("bridge/facade.stamp");
    let mut stamp = format!("format {FORMAT}\n");
    for input in &inputs {
        let meta = std::fs::metadata(input).map_err(|e| format!("{}: {e}", input.display()))?;
        let modified = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map_or(0, |d| d.as_secs());
        stamp += &format!("{} {} {modified}\n", input.display(), meta.len());
    }
    if out.is_file() && std::fs::read_to_string(&stamp_path).ok().as_deref() == Some(stamp.as_str())
    {
        return Ok(out);
    }
    let started = std::time::Instant::now();
    let stats = build(&inputs, &out)?;
    std::fs::write(&stamp_path, stamp).map_err(|e| format!("{}: {e}", stamp_path.display()))?;
    log::info!(
        "Java mods: facade built in {} ms: {} classes, {} bodies stubbed ({})",
        started.elapsed().as_millis(),
        stats.classes,
        stats.stubbed,
        out.display()
    );
    Ok(out)
}

/// The game's own libraries (DataFixerUpper, fastutil, Guava, JOML...) as `horizon-prepare`
/// copied them, checked, into `.local/minecraft/<version>/libraries/client/`: game classes
/// extend and name them, so Fabric's class loader needs them beside the facade. Sorted.
pub fn game_libraries(root: &Path) -> Result<Vec<PathBuf>, String> {
    let lock = horizon_prepare::artifacts::Lock::read(root)?;
    let dir = lock.directory.join("libraries/client");
    let mut jars = Vec::new();
    let mut stack = vec![dir.clone()];
    while let Some(next) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&next) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
            } else if path.extension().is_some_and(|e| e == "jar") {
                jars.push(path);
            }
        }
    }
    if jars.is_empty() {
        return Err(format!(
            "no game libraries in {}: run horizon-prepare --prepare first",
            dir.display()
        ));
    }
    jars.sort();
    Ok(jars)
}

/// What [`build`] did.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Stats {
    /// Classes written to the facade.
    pub classes: usize,
    /// Method bodies replaced.
    pub stubbed: usize,
    /// Class files left out: a later jar's copy of a class already written, `module-info`,
    /// multi-release versions.
    pub skipped: usize,
}

/// Writes the facade of `inputs` (the client jar first, then the server jar: the first copy
/// of a class wins) to `out`.
pub fn build(inputs: &[PathBuf], out: &Path) -> Result<Stats, String> {
    let mut stats = Stats::default();
    let mut seen = std::collections::HashSet::new();
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    let partial = out.with_extension("jar.partial");
    let file =
        std::fs::File::create(&partial).map_err(|e| format!("{}: {e}", partial.display()))?;
    let mut writer = zip::ZipWriter::new(std::io::BufWriter::new(file));
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .last_modified_time(zip::DateTime::default());
    for input in inputs {
        let file = std::fs::File::open(input).map_err(|e| format!("{}: {e}", input.display()))?;
        let mut archive =
            zip::ZipArchive::new(file).map_err(|e| format!("{}: {e}", input.display()))?;
        for i in 0..archive.len() {
            let mut entry = archive
                .by_index(i)
                .map_err(|e| format!("{}: {e}", input.display()))?;
            let name = entry.name().to_string();
            if !name.ends_with(".class") {
                continue;
            }
            if name.starts_with("META-INF/") || name.ends_with("module-info.class") {
                stats.skipped += 1;
                continue;
            }
            if !seen.insert(name.clone()) {
                stats.skipped += 1;
                continue;
            }
            let mut bytes = Vec::with_capacity(entry.size() as usize);
            entry
                .read_to_end(&mut bytes)
                .map_err(|e| format!("{name}: {e}"))?;
            let (stub, stubbed) = stub_class(&bytes).map_err(|e| format!("{name}: {e}"))?;
            writer
                .start_file(name.as_str(), options)
                .map_err(|e| format!("{name}: {e}"))?;
            writer
                .write_all(&stub)
                .map_err(|e| format!("{name}: {e}"))?;
            stats.classes += 1;
            stats.stubbed += stubbed;
        }
    }
    writer
        .finish()
        .map_err(|e| format!("{}: {e}", partial.display()))?;
    std::fs::rename(&partial, out).map_err(|e| format!("{}: {e}", out.display()))?;
    Ok(stats)
}

/// One class file with its method bodies replaced. Returns the new class file and how many
/// bodies were replaced.
pub fn stub_class(bytes: &[u8]) -> Result<(Vec<u8>, usize), String> {
    let class = Class::parse(bytes)?;
    let code_name = class
        .pool
        .utf8_index("Code")
        .ok_or("no \"Code\" in the constant pool")
        .or_else(|e| if class.has_code() { Err(e) } else { Ok(0) })?;
    let mut out = Vec::with_capacity(bytes.len() / 2);
    out.extend_from_slice(&bytes[..class.pool_end]);
    put_u16(&mut out, class.access);
    put_u16(&mut out, class.this_class);
    put_u16(&mut out, class.super_class);
    put_u16(&mut out, class.interfaces.len() as u16);
    for &i in &class.interfaces {
        put_u16(&mut out, i);
    }
    put_u16(&mut out, class.fields.len() as u16);
    for field in &class.fields {
        out.extend_from_slice(field.raw);
    }
    let this_name = class.pool.class_name(class.this_class).unwrap_or_default();
    let super_name = class.pool.class_name(class.super_class).unwrap_or_default();
    let mut stubbed = 0;
    put_u16(&mut out, class.methods.len() as u16);
    for method in &class.methods {
        put_u16(&mut out, method.access);
        put_u16(&mut out, method.name);
        put_u16(&mut out, method.descriptor);
        put_u16(&mut out, method.attributes.len() as u16);
        for attribute in &method.attributes {
            if attribute.name == "Code" {
                let name = class.pool.utf8(method.name).unwrap_or_default();
                let descriptor = class.pool.utf8(method.descriptor).unwrap_or_default();
                let is_static = method.access & ACC_STATIC != 0;
                let body = if name == "<init>" {
                    let call = delegate_call(&class.pool, attribute.body, this_name, super_name)?;
                    constructor_stub(descriptor, call)?
                } else {
                    method_stub(descriptor, is_static)?
                };
                put_u16(&mut out, code_name);
                put_u32(&mut out, body.len() as u32);
                out.extend_from_slice(&body);
                stubbed += 1;
            } else {
                out.extend_from_slice(attribute.raw);
            }
        }
    }
    out.extend_from_slice(&bytes[class.class_attributes..]);
    Ok((out, stubbed))
}

const ACC_STATIC: u16 = 0x0008;

/// A method's parameter types and return type, as descriptor characters (`I`, `J`, `L`...).
fn signature(descriptor: &str) -> Result<(Vec<u8>, u8), String> {
    let bytes = descriptor.as_bytes();
    let bad = || format!("bad method descriptor {descriptor}");
    if bytes.first() != Some(&b'(') {
        return Err(bad());
    }
    let mut params = Vec::new();
    let mut i = 1;
    let read_type = |i: &mut usize| -> Result<u8, String> {
        let start = *i;
        while bytes.get(*i) == Some(&b'[') {
            *i += 1;
        }
        let kind = *bytes.get(*i).ok_or_else(bad)?;
        if kind == b'L' {
            while bytes.get(*i) != Some(&b';') {
                *i += 1;
                if *i >= bytes.len() {
                    return Err(bad());
                }
            }
        }
        *i += 1;
        Ok(if *i - start > 1 && bytes[start] == b'[' {
            b'['
        } else {
            kind
        })
    };
    while bytes.get(i) != Some(&b')') {
        if i >= bytes.len() {
            return Err(bad());
        }
        params.push(read_type(&mut i)?);
    }
    i += 1;
    let ret = read_type(&mut i)?;
    Ok((params, ret))
}

fn slots(kind: u8) -> u16 {
    match kind {
        b'J' | b'D' => 2,
        _ => 1,
    }
}

/// The opcode that pushes `kind`'s default value.
fn default_push(kind: u8) -> u8 {
    match kind {
        b'J' => 0x09,        // lconst_0
        b'F' => 0x0b,        // fconst_0
        b'D' => 0x0e,        // dconst_0
        b'L' | b'[' => 0x01, // aconst_null
        _ => 0x03,           // iconst_0
    }
}

fn return_op(kind: u8) -> u8 {
    match kind {
        b'V' => 0xb1,
        b'J' => 0xad,
        b'F' => 0xae,
        b'D' => 0xaf,
        b'L' | b'[' => 0xb0,
        _ => 0xac,
    }
}

/// A Code attribute's body: max stack, max locals, the code, no handlers, no attributes.
fn code_body(max_stack: u16, max_locals: u16, code: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(12 + code.len());
    put_u16(&mut body, max_stack);
    put_u16(&mut body, max_locals);
    put_u32(&mut body, code.len() as u32);
    body.extend_from_slice(code);
    put_u16(&mut body, 0);
    put_u16(&mut body, 0);
    body
}

fn locals(params: &[u8], is_static: bool) -> u16 {
    params.iter().map(|&p| slots(p)).sum::<u16>() + u16::from(!is_static)
}

fn method_stub(descriptor: &str, is_static: bool) -> Result<Vec<u8>, String> {
    let (params, ret) = signature(descriptor)?;
    let mut code = Vec::new();
    if ret != b'V' {
        code.push(default_push(ret));
    }
    code.push(return_op(ret));
    Ok(code_body(slots(ret), locals(&params, is_static), &code))
}

/// The constructor call a constructor makes on its own object: the methodref's index and
/// its descriptor.
struct Delegate {
    methodref: u16,
    descriptor: String,
}

fn constructor_stub(descriptor: &str, call: Delegate) -> Result<Vec<u8>, String> {
    let (params, _) = signature(descriptor)?;
    let (args, _) = signature(&call.descriptor)?;
    let mut code = vec![0x2a]; // aload_0
    for &arg in &args {
        code.push(default_push(arg));
    }
    code.push(0xb7); // invokespecial
    code.extend_from_slice(&call.methodref.to_be_bytes());
    code.push(0xb1); // return
    let max_stack = 1 + args.iter().map(|&a| slots(a)).sum::<u16>();
    Ok(code_body(max_stack, locals(&params, false), &code))
}

/// Finds the `super(...)` or `this(...)` call in a constructor's code: the first
/// `invokespecial <init>` that doesn't initialize an object a `new` made first.
fn delegate_call(
    pool: &Pool,
    code_attr: &[u8],
    this: &str,
    superclass: &str,
) -> Result<Delegate, String> {
    let code_len = read_u32(code_attr, 4)? as usize;
    let code = code_attr
        .get(8..8 + code_len)
        .ok_or("Code attribute shorter than its code")?;
    let mut pending: Vec<&str> = Vec::new();
    let mut pc = 0;
    while pc < code.len() {
        let op = code[pc];
        match op {
            0xbb => {
                // new
                let index = read_u16(code, pc + 1)?;
                pending.push(pool.class_name(index).unwrap_or_default());
            }
            0xb7 => {
                // invokespecial
                let index = read_u16(code, pc + 1)?;
                if let Some((owner, "<init>", descriptor)) = pool.member(index) {
                    if let Some(at) = pending.iter().rposition(|p| *p == owner) {
                        pending.remove(at);
                    } else if owner == this || owner == superclass {
                        return Ok(Delegate {
                            methodref: index,
                            descriptor: descriptor.to_string(),
                        });
                    }
                }
            }
            _ => {}
        }
        pc += instruction_length(code, pc)?;
    }
    Err(format!(
        "a constructor of {this} calls no super or this constructor"
    ))
}

/// The length of the instruction at `pc`, operands included.
fn instruction_length(code: &[u8], pc: usize) -> Result<usize, String> {
    let op = code[pc];
    Ok(match op {
        0x10 | 0x12 | 0x15..=0x19 | 0x36..=0x3a | 0xa9 | 0xbc => 2,
        0x11
        | 0x13
        | 0x14
        | 0x84
        | 0x99..=0xa8
        | 0xb2..=0xb8
        | 0xbb
        | 0xbd
        | 0xc0
        | 0xc1
        | 0xc6
        | 0xc7 => 3,
        0xc5 => 4,
        0xb9 | 0xba | 0xc8 | 0xc9 => 5,
        0xaa | 0xab => {
            let base = pc + 1;
            let padded = base + (4 - base % 4) % 4;
            if op == 0xaa {
                let low = read_u32(code, padded + 4)? as i32;
                let high = read_u32(code, padded + 8)? as i32;
                let count = (high - low + 1).max(0) as usize;
                padded + 12 + count * 4 - pc
            } else {
                let pairs = read_u32(code, padded + 4)? as usize;
                padded + 8 + pairs * 8 - pc
            }
        }
        0xc4 => {
            if code.get(pc + 1) == Some(&0x84) {
                6
            } else {
                4
            }
        }
        _ => 1,
    })
}

// ---- class file reading --------------------------------------------------------------

struct Class<'a> {
    pool: Pool<'a>,
    /// Where the constant pool ends (the access flags start).
    pool_end: usize,
    access: u16,
    this_class: u16,
    super_class: u16,
    interfaces: Vec<u16>,
    fields: Vec<Member<'a>>,
    methods: Vec<Member<'a>>,
    /// Where the class's attributes (their count first) start.
    class_attributes: usize,
}

struct Member<'a> {
    access: u16,
    name: u16,
    descriptor: u16,
    attributes: Vec<Attribute<'a>>,
    /// The whole member, for copying it unchanged.
    raw: &'a [u8],
}

struct Attribute<'a> {
    name: &'a str,
    /// Its body, after the name and length.
    body: &'a [u8],
    /// The whole attribute, header included.
    raw: &'a [u8],
}

impl Class<'_> {
    fn has_code(&self) -> bool {
        self.methods
            .iter()
            .any(|m| m.attributes.iter().any(|a| a.name == "Code"))
    }
}

#[derive(Clone, Copy)]
enum Entry<'a> {
    Utf8(&'a str),
    Class(u16),
    Member(u16, u16),
    NameAndType(u16, u16),
    Other,
}

struct Pool<'a> {
    entries: Vec<Entry<'a>>,
    utf8: HashMap<&'a str, u16>,
}

impl<'a> Pool<'a> {
    fn utf8(&self, index: u16) -> Option<&'a str> {
        match self.entries.get(index as usize)? {
            Entry::Utf8(s) => Some(s),
            _ => None,
        }
    }
    fn utf8_index(&self, text: &str) -> Option<u16> {
        self.utf8.get(text).copied()
    }
    fn class_name(&self, index: u16) -> Option<&'a str> {
        match self.entries.get(index as usize)? {
            Entry::Class(name) => self.utf8(*name),
            _ => None,
        }
    }
    /// A field or method reference: owner, name, descriptor.
    fn member(&self, index: u16) -> Option<(&'a str, &'a str, &'a str)> {
        let Entry::Member(class, nat) = *self.entries.get(index as usize)? else {
            return None;
        };
        let Entry::NameAndType(name, descriptor) = *self.entries.get(nat as usize)? else {
            return None;
        };
        Some((
            self.class_name(class)?,
            self.utf8(name)?,
            self.utf8(descriptor)?,
        ))
    }
}

impl<'a> Class<'a> {
    fn parse(bytes: &'a [u8]) -> Result<Self, String> {
        if read_u32(bytes, 0)? != 0xCAFE_BABE {
            return Err("not a class file".into());
        }
        let count = read_u16(bytes, 8)? as usize;
        let mut entries = vec![Entry::Other; count.max(1)];
        let mut utf8 = HashMap::new();
        let mut at = 10;
        let mut i = 1;
        while i < count {
            let tag = *bytes.get(at).ok_or("constant pool cut short")?;
            at += 1;
            let (entry, len, wide) = match tag {
                1 => {
                    let len = read_u16(bytes, at)? as usize;
                    let raw = bytes
                        .get(at + 2..at + 2 + len)
                        .ok_or("constant pool cut short")?;
                    // Modified UTF-8: names and descriptors are plain UTF-8 in practice; others
                    // (string constants with NUL or surrogates) are never looked up by name.
                    let entry = std::str::from_utf8(raw)
                        .map(Entry::Utf8)
                        .unwrap_or(Entry::Other);
                    if let Entry::Utf8(s) = entry {
                        utf8.entry(s).or_insert(i as u16);
                    }
                    (entry, 2 + len, false)
                }
                3 | 4 => (Entry::Other, 4, false),
                5 | 6 => (Entry::Other, 8, true),
                7 => (Entry::Class(read_u16(bytes, at)?), 2, false),
                8 | 16 | 19 | 20 => (Entry::Other, 2, false),
                9..=11 => (
                    Entry::Member(read_u16(bytes, at)?, read_u16(bytes, at + 2)?),
                    4,
                    false,
                ),
                12 => (
                    Entry::NameAndType(read_u16(bytes, at)?, read_u16(bytes, at + 2)?),
                    4,
                    false,
                ),
                15 => (Entry::Other, 3, false),
                17 | 18 => (Entry::Other, 4, false),
                other => return Err(format!("unknown constant pool tag {other}")),
            };
            entries[i] = entry;
            at += len;
            i += if wide { 2 } else { 1 };
        }
        let pool = Pool { entries, utf8 };
        let pool_end = at;
        let access = read_u16(bytes, at)?;
        let this_class = read_u16(bytes, at + 2)?;
        let super_class = read_u16(bytes, at + 4)?;
        let interface_count = read_u16(bytes, at + 6)? as usize;
        at += 8;
        let mut interfaces = Vec::with_capacity(interface_count);
        for _ in 0..interface_count {
            interfaces.push(read_u16(bytes, at)?);
            at += 2;
        }
        let (fields, next) = members(bytes, at, &pool)?;
        let (methods, next) = members(bytes, next, &pool)?;
        Ok(Class {
            pool,
            pool_end,
            access,
            this_class,
            super_class,
            interfaces,
            fields,
            methods,
            class_attributes: next,
        })
    }
}

fn members<'a>(
    bytes: &'a [u8],
    mut at: usize,
    pool: &Pool<'a>,
) -> Result<(Vec<Member<'a>>, usize), String> {
    let count = read_u16(bytes, at)? as usize;
    at += 2;
    let mut members = Vec::with_capacity(count);
    for _ in 0..count {
        let start = at;
        let access = read_u16(bytes, at)?;
        let name = read_u16(bytes, at + 2)?;
        let descriptor = read_u16(bytes, at + 4)?;
        let attribute_count = read_u16(bytes, at + 6)? as usize;
        at += 8;
        let mut attributes = Vec::with_capacity(attribute_count);
        for _ in 0..attribute_count {
            let attr_start = at;
            let attr_name = pool.utf8(read_u16(bytes, at)?).unwrap_or("");
            let len = read_u32(bytes, at + 2)? as usize;
            let body = bytes
                .get(at + 6..at + 6 + len)
                .ok_or("attribute cut short")?;
            at += 6 + len;
            attributes.push(Attribute {
                name: attr_name,
                body,
                raw: &bytes[attr_start..at],
            });
        }
        members.push(Member {
            access,
            name,
            descriptor,
            attributes,
            raw: &bytes[start..at],
        });
    }
    Ok((members, at))
}

fn read_u16(bytes: &[u8], at: usize) -> Result<u16, String> {
    bytes
        .get(at..at + 2)
        .map(|b| u16::from_be_bytes([b[0], b[1]]))
        .ok_or_else(|| "class file cut short".to_string())
}

fn read_u32(bytes: &[u8], at: usize) -> Result<u32, String> {
    bytes
        .get(at..at + 4)
        .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
        .ok_or_else(|| "class file cut short".to_string())
}

fn put_u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_be_bytes());
}

fn put_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_be_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn descriptors() {
        assert_eq!(signature("()V").unwrap(), (vec![], b'V'));
        assert_eq!(
            signature("(IJ[Ljava/lang/String;Lfoo/Bar;[[D)Lbaz/Q;").unwrap(),
            (b"IJ[L[".to_vec(), b'L')
        );
        assert!(signature("(I").is_err());
        assert_eq!(locals(b"IJD", false), 6);
    }

    #[test]
    fn switch_lengths() {
        // tableswitch at pc 1: 2 bytes padding, default, low 0, high 1, two offsets.
        let mut code = vec![0x00, 0xaa, 0, 0];
        code.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(instruction_length(&code, 1).unwrap(), 3 + 20);
        assert_eq!(instruction_length(&[0xc4, 0x84, 0, 1, 0, 1], 0).unwrap(), 6);
        assert_eq!(instruction_length(&[0xc4, 0x15, 0, 1], 0).unwrap(), 4);
    }
}
