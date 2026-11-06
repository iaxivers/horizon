//! Mod blocks (a content root's states) whose template has no cached collision shape: they
//! follow the template's rule, and a state with neither a shape nor a rule collides as a full
//! block instead of failing the query (a server thread stopped on "unsupported block
//! collision state" when a player walked into Create's scaffolding).
use horizon_core::{
    geometry::{Aabb, Vec3, VoxelShape},
    position::BlockPos,
};
use horizon_world::{
    DATA_VERSION,
    block_collision::{BlockCollisionRegistry, DynamicCollisionWorld, ShulkerCollisionState},
    collision::CachedCollisionRegistry,
    collision_context::CollisionContext,
    dynamic_support::DynamicSupportWorld,
    entity_obstruction::EntityIdentity,
    fluid::FluidState,
    liquid_collision::LiquidCollisionWorld,
    nbt::Tag,
    powder_snow_collision::{PowderSnowEntity, PowderSnowWorld},
    registry::{BlockRegistry, BlockStateId},
};
use std::sync::Arc;

/// No block entities, no fluids: every query the shapes here make has an answer.
struct World;
impl DynamicSupportWorld for World {
    type Error = String;
    fn shulker_is_closed(&mut self, _: [i32; 3]) -> Result<Option<bool>, String> {
        Ok(None)
    }
    fn moving_piston_shape(&mut self, _: [i32; 3]) -> Result<Option<VoxelShape>, String> {
        Ok(None)
    }
}
impl DynamicCollisionWorld for World {
    fn shulker_collision_state(
        &mut self,
        _: [i32; 3],
    ) -> Result<Option<ShulkerCollisionState>, String> {
        Ok(None)
    }
}
impl LiquidCollisionWorld for World {
    type Error = String;
    fn entity_liquid_shape(&mut self, _: EntityIdentity) -> Result<VoxelShape, String> {
        Err("no entities".into())
    }
    fn fluid(&mut self, _: BlockPos) -> Result<FluidState, String> {
        Err("no fluids".into())
    }
    fn entity_can_stand(&mut self, _: EntityIdentity, _: FluidState) -> Result<bool, String> {
        Err("no entities".into())
    }
}
impl PowderSnowWorld for World {
    type Error = String;
    fn powder_snow_entity(&mut self, _: EntityIdentity) -> Result<PowderSnowEntity, String> {
        Err("no entities".into())
    }
    fn powder_snow_walkable_tag(&mut self, _: EntityIdentity) -> Result<bool, String> {
        Err("no entities".into())
    }
    fn feet_are_leather_boots(&mut self, _: EntityIdentity) -> Result<bool, String> {
        Err("no entities".into())
    }
}

fn scaffold(name: &str, distance: u8) -> Tag {
    Tag::compound([
        ("id", Tag::String(name.into())),
        (
            "properties",
            Tag::compound([
                ("bottom", Tag::String("false".into())),
                (
                    "distance",
                    Tag::String(distance.to_string().as_str().into()),
                ),
                ("waterlogged", Tag::String("false".into())),
            ]),
        ),
    ])
}

/// air, vanilla scaffolding, a mod's copy of it, and a mod block with no rule at all.
fn fixture(templates: Option<Tag>) -> (BlockRegistry, Tag) {
    let states = vec![
        Tag::String("minecraft:air".into()),
        scaffold("minecraft:scaffolding", 0),
        scaffold("create:andesite_scaffolding", 0),
        Tag::String("othermod:odd_block".into()),
    ];
    let registry = BlockRegistry::from_nbt(&Tag::compound([
        ("DataVersion", Tag::Int(DATA_VERSION)),
        ("states", Tag::List(states.clone())),
        (
            "defaults",
            Tag::compound([
                ("minecraft:air", Tag::Int(0)),
                ("minecraft:scaffolding", Tag::Int(1)),
                ("create:andesite_scaffolding", Tag::Int(2)),
                ("othermod:odd_block", Tag::Int(3)),
            ]),
        ),
    ]))
    .unwrap();
    let empty = Tag::compound([
        ("x", Tag::LongArray(vec![0])),
        ("y", Tag::LongArray(vec![0])),
        ("z", Tag::LongArray(vec![0])),
        ("occupied", Tag::ByteArray(vec![])),
        ("cube", Tag::Byte(0)),
        ("cube_axes", Tag::Byte(0)),
        ("discrete_min", Tag::IntArray(vec![0; 3])),
        ("discrete_max", Tag::IntArray(vec![0; 3])),
    ]);
    let mut collision = vec![
        ("DataVersion", Tag::Int(DATA_VERSION)),
        ("states", Tag::List(states)),
        ("shapes", Tag::List(vec![empty])),
        // Scaffolding has no cached shape (`ScaffoldingBlock.getCollisionShape` reads the
        // entity); the mod blocks' rows are copies of it.
        ("cached_shapes", Tag::IntArray(vec![0, -1, -1, -1])),
    ];
    if let Some(templates) = templates {
        collision.push(("templates", templates));
    }
    (registry, Tag::compound(collision))
}

fn shapes(collisions: &BlockCollisionRegistry<'_>, state: u32, feet: f64) -> VoxelShape {
    let context = CollisionContext::position(feet);
    collisions
        .collision_shape_with_context(
            &mut World,
            BlockStateId(state),
            BlockPos::new(0, 0, 0),
            &context,
        )
        .unwrap_or_else(|e| panic!("state {state}, feet at {feet}: {e}"))
}

#[test]
fn a_mod_scaffolding_collides_as_vanilla_scaffolding() {
    let templates = Tag::compound([(
        "create:andesite_scaffolding",
        Tag::String("minecraft:scaffolding".into()),
    )]);
    let (registry, tag) = fixture(Some(templates));
    let cached = CachedCollisionRegistry::from_nbt(&tag, &registry).unwrap();
    let collisions = BlockCollisionRegistry::new(Arc::new(cached)).unwrap();
    // Standing on it (feet at its top) and inside it (walking into it).
    for feet in [1.0, 0.5, 0.0] {
        let vanilla = shapes(&collisions, 1, feet);
        let ours = shapes(&collisions, 2, feet);
        assert_eq!(ours.boxes(), vanilla.boxes(), "feet at {feet}");
    }
    // Vanilla's own rule, as a check of the comparison: a floor on top, nothing inside.
    assert!(!shapes(&collisions, 2, 1.0).is_empty());
    assert!(shapes(&collisions, 2, 0.5).is_empty());
}

#[test]
fn a_state_with_no_rule_collides_as_a_full_block() {
    // An older content root: no templates. Neither mod block has a rule.
    let (registry, tag) = fixture(None);
    let cached = CachedCollisionRegistry::from_nbt(&tag, &registry).unwrap();
    let collisions = BlockCollisionRegistry::new(Arc::new(cached)).unwrap();
    let block = Aabb::new(Vec3::ZERO, Vec3::new(1.0, 1.0, 1.0));
    for state in [2, 3] {
        for feet in [1.0, 0.5] {
            assert_eq!(shapes(&collisions, state, feet).boxes(), [block]);
        }
        let plain = collisions
            .collision_shape(&mut World, BlockStateId(state), [0, 0, 0])
            .unwrap();
        assert_eq!(plain.boxes(), [block]);
    }
    // Vanilla's scaffolding keeps its rule.
    assert!(shapes(&collisions, 1, 0.5).is_empty());
}

#[test]
fn a_template_whose_rule_does_not_fit_falls_back() {
    // A mod block said to copy scaffolding, without scaffolding's properties: the
    // registry still builds, and the block still answers with something to stand on.
    let templates = Tag::compound([(
        "othermod:odd_block",
        Tag::String("minecraft:scaffolding".into()),
    )]);
    let (registry, tag) = fixture(Some(templates));
    let cached = CachedCollisionRegistry::from_nbt(&tag, &registry).unwrap();
    let collisions = BlockCollisionRegistry::new(Arc::new(cached)).unwrap();
    for feet in [1.0, 0.5] {
        assert!(!shapes(&collisions, 3, feet).is_empty(), "feet at {feet}");
    }
}
