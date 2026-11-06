//! Mod blocks (a content root's) in the collision registry: a mod block copying a
//! vanilla template follows the template's rule, and a state with neither a cached
//! shape nor a rule collides as a full cube.
use super::*;
use crate::chunk::BlockState;
use std::borrow::Cow;

/// A state's rules, as [`BlockCollisionRegistry::new`] files them.
pub(super) struct StateRules<'r> {
    /// The state the rules read: a mod block's template, with the mod block's properties.
    pub(super) state: Cow<'r, BlockState>,
    pub(super) contextual: Option<ContextualRule>,
    pub(super) dynamic: Option<DynamicSupport>,
    /// No cached shape and no rule: a full cube.
    pub(super) fallback: bool,
}
impl<'r> StateRules<'r> {
    pub(super) fn new(
        cached: &CachedCollisionRegistry<'r>,
        id: BlockStateId,
        named: &'r BlockState,
    ) -> io::Result<Self> {
        let cached_shape = cached.cached_shape(id)?;
        // A mod block copying a vanilla template (a content root's) follows
        // the template's rule: Create's scaffoldings are vanilla scaffolding
        // (`ScaffoldingBlock` subclasses). Its own properties may not fit the
        // rule: then it has none, rather than the registry failing.
        let template = cached.template(&named.name).map(|template| BlockState {
            name: template.to_owned(),
            properties: named.properties.clone(),
        });
        let lenient = template.is_some();
        let state = template.map_or(Cow::Borrowed(named), Cow::Owned);
        let rule = |result: io::Result<Option<ContextualRule>>| match result {
            Err(_) if lenient => Ok(None),
            other => other,
        };
        let contextual = rule(
            ScaffoldingCollision::from_state(&state).map(|r| r.map(ContextualRule::Scaffolding)),
        )?
        .or(rule(
            LiquidCollision::from_state(&state).map(|r| r.map(ContextualRule::Liquid)),
        )?)
        .or((state.name == "minecraft:powder_snow").then_some(ContextualRule::PowderSnow));
        let mut dynamic = None;
        if cached_shape.is_none() {
            match DynamicSupport::from_state(&state) {
                Ok(Some(rule)) => dynamic = Some(rule),
                Err(_) if lenient => {}
                Err(error) => return Err(error),
                Ok(None) => {}
            }
        }
        // No cached shape and no rule: a mod block whose shape depends on
        // context in a way Horizon doesn't know. A full cube, never a stop
        // (players walk on it and can't walk through it).
        let fallback = cached_shape.is_none() && dynamic.is_none();
        Ok(Self {
            state,
            contextual,
            dynamic,
            fallback,
        })
    }
}

/// Logs the states that collide as full blocks for want of a rule.
pub(super) fn warn_fallback(
    cached: &CachedCollisionRegistry<'_>,
    fallback: &HashMap<BlockStateId, VoxelShape>,
) {
    if !fallback.is_empty() {
        let mut names: Vec<&str> = fallback
            .keys()
            .filter_map(|id| cached.registry().state(*id))
            .map(|state| state.name.as_str())
            .collect();
        names.sort_unstable();
        names.dedup();
        // Once a process: the client, the server and worldgen each build a registry.
        static LOGGED: std::sync::Mutex<Vec<String>> = std::sync::Mutex::new(Vec::new());
        let message = format!(
            "{} block states have no collision rule; they collide as full blocks: {}",
            fallback.len(),
            names.join(", ")
        );
        let mut logged = LOGGED.lock().unwrap_or_else(|e| e.into_inner());
        if !logged.contains(&message) {
            log::warn!("Horizon: {message}");
            logged.push(message);
        }
    }
}
