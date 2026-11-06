//! [`BakedQuad`]: a baked face's quad, without the material its layer replaces.
use super::ModelQuad;

/// A baked quad: [`ModelQuad`] without its material, whose sprite is the face's `layer` once
/// baked (a modded pack's 2.3 million faces carried the name: 32 bytes and a heap string each).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BakedQuad {
    pub positions: [[f32; 3]; 4],
    pub uv: [[f32; 2]; 4],
    pub facing: horizon_assets::model::Direction,
    pub cull: Option<horizon_assets::model::Direction>,
    pub tint_index: i32,
    pub shade_direction_override: Option<horizon_assets::model::Direction>,
    pub light_emission: u8,
    pub light: [u8; 2],
    pub color: Option<i32>,
    pub ambient_occlusion: Option<bool>,
    pub render_type: Option<horizon_assets::bake::RenderType>,
    pub shade: bool,
}
impl From<ModelQuad> for BakedQuad {
    fn from(q: ModelQuad) -> Self {
        Self {
            positions: q.positions,
            uv: q.uv,
            facing: q.facing,
            cull: q.cull,
            tint_index: q.tint_index,
            shade_direction_override: q.shade_direction_override,
            light_emission: q.light_emission,
            light: q.light,
            color: q.color,
            ambient_occlusion: q.ambient_occlusion,
            render_type: q.render_type,
            shade: q.shade,
        }
    }
}
