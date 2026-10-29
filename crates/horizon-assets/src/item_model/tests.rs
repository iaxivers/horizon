use super::*;

#[test]
fn identifier_adds_default_namespace() {
    assert_eq!(identifier("stone").unwrap(), "minecraft:stone");
    assert_eq!(identifier("a:b").unwrap(), "a:b");
}

#[test]
fn positive_modulo_wraps_negatives() {
    assert!((positive_modulo(-0.25, 1.0) - 0.75).abs() < 1e-6);
    assert!((positive_modulo(1.5, 1.0) - 0.5).abs() < 1e-6);
}

#[test]
fn display_context_names_round_trip() {
    for name in ["gui", "ground", "fixed", "head"] {
        assert_eq!(DisplayContext::by_name(name).unwrap().name(), name);
    }
}

#[test]
fn mods_own_model_parts_fall_back() {
    // Farmer's Delight's skillet: a condition on its own property, over its own type.
    let skillet = serde_json::json!({
        "type": "minecraft:condition",
        "property": "farmersdelight:skillet/is_cooking",
        "on_true": {"type": "farmersdelight:skillet_flip", "model": "farmersdelight:item/skillet_cooking"},
        "on_false": {"type": "minecraft:model", "model": "farmersdelight:item/skillet"}
    });
    match ItemModelNode::decode(&skillet).unwrap() {
        ItemModelNode::Model(leaf) => assert_eq!(leaf.model, "farmersdelight:item/skillet"),
        other => panic!("{other:?}"),
    }
    let flip = serde_json::json!({"type": "farmersdelight:skillet_flip", "model": "farmersdelight:item/skillet_cooking"});
    match ItemModelNode::decode(&flip).unwrap() {
        ItemModelNode::Model(leaf) => assert_eq!(leaf.model, "farmersdelight:item/skillet_cooking"),
        other => panic!("{other:?}"),
    }
    // Vanilla's own names stay strict.
    assert!(ItemModelNode::decode(&serde_json::json!({"type": "minecraft:nope"})).is_err());
}
