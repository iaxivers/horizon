//! renderpearl's SPIR-V made readable by naga.
//!
//! 26.3's shaders are GLSL compiled by shaderc for Vulkan (`GlslCompiler`): a `uniform
//! sampler2D Sampler0` is one descriptor, an `OpTypeSampledImage` variable. WGSL (and naga's
//! SPIR-V reader) has textures and samplers apart, so each such variable becomes a texture
//! (its own set and binding: group 0) and a sampler (group 1, the same binding); each load of
//! it becomes a load of both and an `OpSampledImage`, which naga reads. Functions taking a
//! `sampler2D` (vanilla's `sample_lightmap`) take both halves instead, and calls pass both.
//! Line debug info (`OpLine`: shaderc's `generate_debug_info`) is left out: naga's reader
//! refuses it among types.

use std::collections::HashMap;

const OP_NAME: u32 = 5;
const OP_LINE: u32 = 8;
const OP_ENTRY_POINT: u32 = 15;
const OP_TYPE_SAMPLER: u32 = 26;
const OP_TYPE_SAMPLED_IMAGE: u32 = 27;
const OP_TYPE_POINTER: u32 = 32;
const OP_TYPE_FUNCTION: u32 = 33;
const OP_FUNCTION: u32 = 54;
const OP_FUNCTION_PARAMETER: u32 = 55;
const OP_FUNCTION_CALL: u32 = 57;
const OP_VARIABLE: u32 = 59;
const OP_LOAD: u32 = 61;
const OP_ACCESS_CHAIN: u32 = 65;
const OP_DECORATE: u32 = 71;
const OP_COPY_OBJECT: u32 = 83;
const OP_SAMPLED_IMAGE: u32 = 86;
const OP_NO_LINE: u32 = 317;
const UNIFORM_CONSTANT: u32 = 0;
const DECORATION_SET: u32 = 34;

/// The sampler group: samplers split from combined image samplers bind there.
pub const SAMPLER_GROUP: u32 = 1;

struct Inst<'a> {
    op: u32,
    words: &'a [u32],
}

fn instructions(words: &[u32]) -> Result<Vec<Inst<'_>>, String> {
    let mut out = Vec::new();
    let mut i = 5;
    while i < words.len() {
        let count = (words[i] >> 16) as usize;
        if count == 0 || i + count > words.len() {
            return Err("malformed SPIR-V".into());
        }
        out.push(Inst {
            op: words[i] & 0xffff,
            words: &words[i..i + count],
        });
        i += count;
    }
    Ok(out)
}

fn inst(op: u32, operands: &[u32]) -> Vec<u32> {
    let mut v = Vec::with_capacity(operands.len() + 1);
    v.push(((operands.len() as u32 + 1) << 16) | op);
    v.extend_from_slice(operands);
    v
}

/// The words a literal string takes at `words[0..]` (null-terminated, padded).
fn string_words(words: &[u32]) -> usize {
    for (i, w) in words.iter().enumerate() {
        if w.to_le_bytes().contains(&0) {
            return i + 1;
        }
    }
    words.len()
}

/// A split combined sampler: where its image and sampler pointers are, and the types of the
/// image and of the sampled image.
#[derive(Clone, Copy)]
struct Split {
    image: u32,
    sampler: u32,
    image_type: u32,
    sampled_type: u32,
}

/// The module with its combined image samplers split and its line debug info left out.
pub fn split_samplers(words: &[u32]) -> Result<Vec<u32>, String> {
    if words.len() < 5 || words[0] != 0x0723_0203 {
        return Err("not SPIR-V".into());
    }
    let insts = instructions(words)?;
    let mut sampled = HashMap::new(); // sampled image type -> image type
    let mut sampler_type = None;
    let mut pointers = HashMap::new(); // (storage, pointee) -> pointer type
    for i in &insts {
        match i.op {
            OP_TYPE_SAMPLED_IMAGE => {
                sampled.insert(i.words[1], i.words[2]);
            }
            OP_TYPE_SAMPLER => sampler_type = Some(i.words[1]),
            OP_TYPE_POINTER => {
                pointers.insert((i.words[2], i.words[3]), i.words[1]);
            }
            _ => {}
        }
    }
    // Pointer types to sampled images: pointer -> (sampled image type, image type).
    let combined_pointers: HashMap<u32, (u32, u32)> = pointers
        .iter()
        .filter(|((storage, _), _)| *storage == UNIFORM_CONSTANT)
        .filter_map(|((_, pointee), &p)| sampled.get(pointee).map(|&image| (p, (*pointee, image))))
        .collect();
    let uses_combined = insts.iter().any(|i| {
        (i.op == OP_VARIABLE || i.op == OP_FUNCTION_PARAMETER)
            && combined_pointers.contains_key(&i.words[1])
    });
    if !uses_combined {
        let mut out = words[..5].to_vec();
        for i in &insts {
            if i.op != OP_LINE && i.op != OP_NO_LINE {
                out.extend_from_slice(i.words);
            }
        }
        return Ok(out);
    }
    let mut bound = words[3];
    let mut fresh = || {
        bound += 1;
        bound - 1
    };
    // New types and globals, before the first function.
    let mut globals = Vec::new();
    let sampler_type = match sampler_type {
        Some(t) => t,
        None => {
            let t = fresh();
            globals.extend(inst(OP_TYPE_SAMPLER, &[t]));
            t
        }
    };
    let sampler_pointer = match pointers.get(&(UNIFORM_CONSTANT, sampler_type)) {
        Some(&p) => p,
        None => {
            let p = fresh();
            globals.extend(inst(OP_TYPE_POINTER, &[p, UNIFORM_CONSTANT, sampler_type]));
            p
        }
    };
    // Image type -> its UniformConstant pointer (made where the module has none).
    let mut image_pointers: HashMap<u32, u32> = HashMap::new();
    for &(_, image) in combined_pointers.values() {
        if image_pointers.contains_key(&image) {
            continue;
        }
        let p = match pointers.get(&(UNIFORM_CONSTANT, image)) {
            Some(&p) => p,
            None => {
                let p = fresh();
                globals.extend(inst(OP_TYPE_POINTER, &[p, UNIFORM_CONSTANT, image]));
                p
            }
        };
        image_pointers.insert(image, p);
    }
    // Function types taking combined pointers: old -> new (each such parameter as two).
    let mut function_types = HashMap::new();
    for i in &insts {
        if i.op != OP_TYPE_FUNCTION
            || !i.words[3..]
                .iter()
                .any(|p| combined_pointers.contains_key(p))
        {
            continue;
        }
        let id = fresh();
        let mut operands = vec![id, i.words[2]];
        for p in &i.words[3..] {
            match combined_pointers.get(p) {
                Some(&(_, image)) => operands.extend([image_pointers[&image], sampler_pointer]),
                None => operands.push(*p),
            }
        }
        globals.extend(inst(OP_TYPE_FUNCTION, &operands));
        function_types.insert(i.words[1], id);
    }
    let mut split: HashMap<u32, Split> = HashMap::new();
    let mut variables: Vec<_> = insts
        .iter()
        .filter(|i| i.op == OP_VARIABLE && i.words[3] == UNIFORM_CONSTANT)
        .filter_map(|i| {
            combined_pointers
                .get(&i.words[1])
                .map(|&(s, image)| (i.words[2], s, image))
        })
        .collect();
    variables.sort();
    for (old, sampled_type, image_type) in variables {
        let (image, sampler) = (fresh(), fresh());
        globals.extend(inst(
            OP_VARIABLE,
            &[image_pointers[&image_type], image, UNIFORM_CONSTANT],
        ));
        globals.extend(inst(
            OP_VARIABLE,
            &[sampler_pointer, sampler, UNIFORM_CONSTANT],
        ));
        split.insert(
            old,
            Split {
                image,
                sampler,
                image_type,
                sampled_type,
            },
        );
    }
    let mut out = Vec::with_capacity(words.len() + globals.len() + 64);
    out.extend_from_slice(&words[..5]);
    let mut globals = Some(globals);
    for i in &insts {
        match i.op {
            OP_LINE | OP_NO_LINE => {}
            OP_ENTRY_POINT => {
                let name = string_words(&i.words[3..]);
                let mut operands = i.words[1..3 + name].to_vec();
                for &id in &i.words[3 + name..] {
                    match split.get(&id) {
                        Some(s) => operands.extend([s.image, s.sampler]),
                        None => operands.push(id),
                    }
                }
                out.extend(inst(OP_ENTRY_POINT, &operands));
            }
            OP_NAME if split.contains_key(&i.words[1]) => {
                let s = split[&i.words[1]];
                let mut operands = i.words[1..].to_vec();
                operands[0] = s.image;
                out.extend(inst(OP_NAME, &operands));
                operands[0] = s.sampler;
                out.extend(inst(OP_NAME, &operands));
            }
            OP_DECORATE if split.contains_key(&i.words[1]) => {
                let s = split[&i.words[1]];
                let mut operands = i.words[1..].to_vec();
                operands[0] = s.image;
                out.extend(inst(OP_DECORATE, &operands));
                operands[0] = s.sampler;
                if operands[1] == DECORATION_SET {
                    operands[2] = SAMPLER_GROUP;
                }
                out.extend(inst(OP_DECORATE, &operands));
            }
            OP_VARIABLE if split.contains_key(&i.words[2]) => {}
            OP_FUNCTION => {
                if let Some(g) = globals.take() {
                    out.extend(g);
                }
                let mut operands = i.words[1..].to_vec();
                if let Some(&t) = function_types.get(&operands[3]) {
                    operands[3] = t;
                }
                out.extend(inst(OP_FUNCTION, &operands));
            }
            OP_FUNCTION_PARAMETER if combined_pointers.contains_key(&i.words[1]) => {
                let (sampled_type, image_type) = combined_pointers[&i.words[1]];
                let sampler = fresh();
                out.extend(inst(
                    OP_FUNCTION_PARAMETER,
                    &[image_pointers[&image_type], i.words[2]],
                ));
                out.extend(inst(OP_FUNCTION_PARAMETER, &[sampler_pointer, sampler]));
                split.insert(
                    i.words[2],
                    Split {
                        image: i.words[2],
                        sampler,
                        image_type,
                        sampled_type,
                    },
                );
            }
            OP_LOAD if split.contains_key(&i.words[3]) => {
                let s = split[&i.words[3]];
                let (a, b) = (fresh(), fresh());
                out.extend(inst(OP_LOAD, &[s.image_type, a, s.image]));
                out.extend(inst(OP_LOAD, &[sampler_type, b, s.sampler]));
                out.extend(inst(OP_SAMPLED_IMAGE, &[s.sampled_type, i.words[2], a, b]));
            }
            OP_FUNCTION_CALL if i.words[4..].iter().any(|a| split.contains_key(a)) => {
                let mut operands = i.words[1..4].to_vec();
                for a in &i.words[4..] {
                    match split.get(a) {
                        Some(s) => operands.extend([s.image, s.sampler]),
                        None => operands.push(*a),
                    }
                }
                out.extend(inst(OP_FUNCTION_CALL, &operands));
            }
            OP_ACCESS_CHAIN | OP_COPY_OBJECT
                if i.words.get(3).is_some_and(|w| split.contains_key(w)) =>
            {
                return Err(format!("a combined image sampler used by op {}", i.op));
            }
            _ => out.extend_from_slice(i.words),
        }
    }
    out[3] = bound;
    Ok(out)
}
