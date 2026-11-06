//! Mod blocks (a content root's) by the vanilla template they copy: a template state
//! with no cached shape has a rule of its own (scaffolding, bamboo...), which the mod
//! block's states follow.
use super::*;

/// The `templates` compound of a collision export (block → its template); none: empty.
pub(super) fn read(templates: Option<&Tag>) -> io::Result<HashMap<String, String>> {
    Ok(match templates {
        None => HashMap::new(),
        Some(Tag::Compound(map)) => map
            .iter()
            .filter_map(|(block, template)| match template {
                Tag::String(template) => {
                    Some((block.to_string_lossy(), template.to_string_lossy()))
                }
                _ => None,
            })
            .collect(),
        Some(_) => return Err(invalid("invalid collision templates")),
    })
}

impl CachedCollisionRegistry<'_> {
    /// The vanilla block a mod block copies (its content root's template), if any.
    pub fn template(&self, block: &str) -> Option<&str> {
        self.templates.get(block).map(String::as_str)
    }
}
