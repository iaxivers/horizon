//! OBJ materials naming a model's texture slot (Create's backtanks).
use super::*;

#[test]
fn mtl_map_without_hash_names_a_slot_when_the_model_has_it() {
    // Create's backtanks: `map_Kd 0` with a texture "0"; a bare name the model has no slot
    // for is still a sprite (minecraft:<name>).
    let files = [
        ("t:models/x.obj", OBJ),
        (
            "t:models/o.mtl",
            "newmtl top\nmap_Kd 0\nnewmtl side\nmap_Kd plain\n",
        ),
    ];
    let model = r#"{"loader":"neoforge:obj","model":"t:models/x.obj","mtl_override":"t:models/o.mtl",
        "textures":{"0":"t:block/tank"}}"#;
    let library = library(&[("t:b", model)], &files);
    let quads = bake(&library, "t:b");
    assert_eq!(quads[0].material.sprite, "t:block/tank");
    assert_eq!(quads[1].material.sprite, "minecraft:plain");
}
