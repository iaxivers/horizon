//! A block's own states as an array and as a `state_table`.
use super::*;

#[test]
fn a_state_table_reads_as_its_states() {
    let array = br#"{"template": "minecraft:stone", "states": [
        {"properties": {"east": "none", "lit": "false"}, "shape": "minecraft:cake[bites=1]", "default": true},
        {"properties": {"east": "pipe", "lit": "false"}},
        {"properties": {"east": "none", "lit": "true"}, "shape": "minecraft:cake[bites=1]", "light": 12},
        {"properties": {"east": "pipe", "lit": "true"}, "random_ticks": true}]}"#;
    let table = br#"{"template": "minecraft:stone", "state_table": {
        "properties": [["east", ["none", "pipe"]], ["lit", ["false", "true"]]],
        "shapes": ["minecraft:cake[bites=1]", null],
        "rows": [[0, 0, 0], [1, 1, 0], [0, 0, 1], [1, 1, 1]],
        "light": {"2": 12}, "random_ticks": [3], "default": 0}}"#;
    let read = |name: &str, text: &[u8]| {
        let files: &[(&str, &[u8])] = &[("data/ruby/horizon/block/pipe.json", text)];
        hmod(name, MANIFEST_TEXT, files).map(|c| c.blocks[0].states.clone())
    };
    let states = read("states-array", array).unwrap();
    assert_eq!(states.len(), 4);
    assert_eq!(read("states-table", table).unwrap(), states);
    for (name, bad) in [
        ("table-shape", r#"[[2, 0, 0]]"#),
        ("table-value", r#"[[0, 2, 0]]"#),
        ("table-width", r#"[[0, 0]]"#),
    ] {
        let text = format!(
            r#"{{"template": "minecraft:stone", "state_table": {{"properties": [["east", ["none", "pipe"]], ["lit", ["false", "true"]]], "shapes": [null], "rows": {bad}}}}}"#
        );
        let e = read(name, text.as_bytes()).unwrap_err();
        assert!(e.contains("state_table"), "{name}: {e}");
    }
    let both = br#"{"template": "minecraft:stone", "states": [{"properties": {"a": "b"}}],
        "state_table": {"properties": [], "shapes": [null], "rows": [[0]]}}"#;
    assert!(read("table-both", both).unwrap_err().contains("not both"));
}
#[test]
fn own_states_read_their_own_boxes_in_either_form() {
    let array = br#"{"template": "minecraft:stone", "states": [
        {"properties": {"lit": "false"}, "shape": "minecraft:stone", "occlusion": [], "default": true},
        {"properties": {"lit": "true"}, "outline": [[0, 0, 0, 1, 0.5, 1]], "collision": [[0, 0, 0, 1, 0.5, 1]],
         "occlusion": [[0, 0, 0, 1, 0.5, 1]]}]}"#;
    let table = br#"{"template": "minecraft:stone", "state_table": {
        "properties": [["lit", ["false", "true"]]],
        "shapes": [{"like": "minecraft:stone", "occlusion": []},
                   {"outline": [[0, 0, 0, 1, 0.5, 1]], "collision": [[0, 0, 0, 1, 0.5, 1]], "occlusion": [[0, 0, 0, 1, 0.5, 1]]}],
        "rows": [[0, 0], [1, 1]], "default": 0}}"#;
    let read = |name: &str, text: &[u8]| {
        let files: &[(&str, &[u8])] = &[("data/ruby/horizon/block/lamp.json", text)];
        hmod(name, MANIFEST_TEXT, files).map(|c| c.blocks[0].states.clone())
    };
    let states = read("own-boxes-array", array).unwrap();
    assert_eq!(states[0].shape.as_deref(), Some("minecraft:stone"));
    assert_eq!(states[0].occlusion, Some(vec![]));
    assert_eq!(
        states[1].outline,
        Some(vec![[0.0, 0.0, 0.0, 1.0, 0.5, 1.0]])
    );
    assert_eq!(read("own-boxes-table", table).unwrap(), states);
    let unknown = br#"{"template": "minecraft:stone", "state_table": {
        "properties": [], "shapes": [{"like": "minecraft:stone", "glow": 1}], "rows": [[0]]}}"#;
    assert!(read("own-boxes-unknown", unknown).is_err());
}
