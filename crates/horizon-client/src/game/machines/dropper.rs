//! `DropperBlock.dispenseFrom` into a container.
use super::*;

impl Game<'_> {
    /// `DropperBlock.dispenseFrom` into a container: one item of `stack` through the face
    /// opposite `facing` (`HopperBlockEntity.addItem`); what stays in the dropper's slot.
    pub(super) fn drop_into(
        &mut self,
        into: &View,
        facing: Direction,
        stack: ItemStack,
    ) -> Option<ItemStack> {
        let one = ItemStack {
            count: 1,
            ..stack.clone()
        };
        if self
            .add_item(None, into, one, Some(facing.opposite()))
            .is_none()
        {
            self.view_changed(into);
            Some(ItemStack {
                count: stack.count - 1,
                ..stack
            })
            .filter(|s| s.count > 0)
        } else {
            Some(stack)
        }
    }
}
