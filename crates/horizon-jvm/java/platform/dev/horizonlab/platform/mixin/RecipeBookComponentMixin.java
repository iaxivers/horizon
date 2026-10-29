package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.GhostSlots;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A ghost recipe is read in the client's level, which the headless client has none of: the
 * player's own level instead (SlotDisplayContextMixin does the same for the book's pages). Its
 * search reads the connection's search trees, without a connection: HorizonClient's.
 */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
    @Shadow @Final private GhostSlots ghostSlots;

    @Shadow
    protected abstract void fillGhostRecipe(GhostSlots slots, RecipeDisplay display, ContextMap context);

    @Inject(method = "fillGhostRecipe(Lnet/minecraft/world/item/crafting/display/RecipeDisplay;)V",
            at = @At("HEAD"), cancellable = true)
    private void horizon$ghost(RecipeDisplay display, CallbackInfo ci) {
        var minecraft = HorizonClient.minecraft();
        if (!HorizonClient.headless() || minecraft.level != null || minecraft.player == null) return;
        ci.cancel();
        ghostSlots.clear();
        fillGhostRecipe(ghostSlots, display, SlotDisplayContext.fromLevel(minecraft.player.level()));
    }

    @Redirect(method = "updateCollections", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;getConnection()Lnet/minecraft/client/multiplayer/ClientPacketListener;"))
    private ClientPacketListener horizon$searchTrees(Minecraft minecraft) {
        var connection = minecraft.getConnection();
        return connection == null && HorizonClient.headless() ? HorizonClient.searchConnection() : connection;
    }
}
