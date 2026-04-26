package diolez.hitregistry.mixin;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.prediction.UtilityPredictionManager;
import diolez.hitregistry.util.TickClock;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ItemUsageMixin — intercepts item use and block placement for utility prediction.
 *
 * Targets:
 *   - ClientPlayerInteractionManager#interactItem  (item use: pearls, food, potions, bows)
 *   - ClientPlayerInteractionManager#interactBlock (block placement)
 *
 * Injection at HEAD lets us record the prediction before vanilla sends the packet.
 * We never cancel — vanilla behaviour is always preserved.
 *
 * ITEM CLASSIFICATION:
 *   The Item class hierarchy in 1.21 is used to classify what kind of action
 *   we're predicting:
 *     EnderPearlItem       → ENDER_PEARL
 *     BowItem / CrossbowItem → PROJECTILE_LAUNCH
 *     FoodItem / DrinkItem → ITEM_USE
 *     (everything else)    → ITEM_USE (generic)
 *
 * BLOCK PLACEMENT:
 *   Vanilla already predicts block placement locally. We record it here
 *   for reconciliation purposes — if the server sends a contradicting
 *   BlockUpdateS2CPacket, we propagate the denial event to UtilityManager.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ItemUsageMixin {

    @Inject(method = "interactItem", at = @At("HEAD"))
    private void hitregistry$onInteractItem(
            PlayerEntity player, Hand hand,
            CallbackInfoReturnable<TypedActionResult<ItemStack>> cir) {

        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isUtilityPredictionEnabled()) return;

        UtilityPredictionManager utility = HitregistryClient.getUtility();
        if (utility == null) return;

        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) return;

        Item item = stack.getItem();
        long tick = TickClock.current();

        if (item instanceof EnderPearlItem) {
            utility.onPearlThrow(tick);
        } else if (item instanceof BowItem || item instanceof CrossbowItem) {
            // Note: bow launches fire on USE_STOP, not USE_START.
            // For crossbows in LOADED state we predict on interactItem.
            // TODO: Separate hook for BowItem charge release (requires
            //       tracking bow draw state, which in 1.21 is server-driven).
            utility.onProjectileLaunch(tick);
        } else {
            // Generic item use — food, potions, milk buckets, etc.
            utility.onItemUseStart(tick, stack);
        }
    }

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void hitregistry$onInteractBlock(
            net.minecraft.client.network.ClientPlayerEntity player,
            World world, Hand hand,
            net.minecraft.util.hit.BlockHitResult hitResult,
            CallbackInfoReturnable<ActionResult> cir) {

        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isBlockPlacementPredictionEnabled()) return;

        UtilityPredictionManager utility = HitregistryClient.getUtility();
        if (utility == null) return;

        // Only predict actual placement (player holding a placeable block)
        ItemStack held = player.getStackInHand(hand);
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem)) return;

        // The placement target position is the face-adjacent position
        BlockPos placePos = hitResult.getBlockPos().offset(hitResult.getSide());
        utility.onBlockPlace(TickClock.current(), placePos);
    }
}
