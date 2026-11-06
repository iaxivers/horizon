//! [`StructureRegistry::unsupported`].
use super::*;

#[test]
fn unsupported_lists_mod_structure_types_of_the_sets_given() {
    let spread = |structures: Value| {
        json!({"structures": structures, "placement": {"type": "minecraft:random_spread",
            "spacing": 8, "separation": 2, "salt": 1}})
    };
    let one = |s: &str| json!([{"structure": s, "weight": 1}]);
    let r = registry(
        &[
            (
                "faf:citadel",
                json!({"type": "faf:citadel_structure", "biomes": "#t:one"}),
            ),
            (
                "faf:shack",
                json!({"type": "faf:shack_structure", "biomes": "#t:one"}),
            ),
            (
                "t:igloo",
                json!({"type": "minecraft:igloo", "biomes": "#t:one"}),
            ),
            (
                "t:far",
                json!({"type": "minecraft:igloo", "biomes": "#t:two"}),
            ),
        ],
        &[
            (
                "faf:a",
                spread(json!([{"structure": "faf:citadel", "weight": 1},
                {"structure": "t:igloo", "weight": 1}])),
            ),
            ("faf:b", spread(one("faf:shack"))),
            ("t:igloos", spread(one("t:igloo"))),
            (
                "t:odd",
                json!({"structures": one("t:far"),
                "placement": {"type": "mod:own_placement", "salt": 2}}),
            ),
        ],
    );
    let index = |name: &str| r.sets.iter().position(|s| s.name == name).unwrap();
    assert_eq!(
        r.unsupported(&[index("faf:a"), index("t:igloos"), index("t:odd")]),
        [
            ("faf:citadel".to_owned(), "faf:citadel_structure".to_owned()),
            ("t:far".to_owned(), "t:odd placement".to_owned()),
        ]
    );
    assert!(r.unsupported(&[index("t:igloos")]).is_empty());
}
