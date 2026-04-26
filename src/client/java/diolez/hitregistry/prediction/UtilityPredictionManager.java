package diolez.hitregistry.prediction;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.config.HitregistryConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * UtilityPredictionManager — eliminates non-combat desyncs.
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  SUPPORTED DESYNCS & HOW WE FIX THEM                           ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║  Ender Pearl      Visual arc shown immediately. If server       ║
 * ║                   rejects (full inventory slot, anti-cheat),    ║
 * ║                   entity is removed from client world.          ║
 * ║                                                                 ║
 * ║  Bow / Crossbow   Projectile entity spawned locally. Removed    ║
 * ║                   if server doesn't echo an AddEntityS2CPacket  ║
 * ║                   within rollbackDelayMs.                       ║
 * ║                                                                 ║
 * ║  Food / Potion    Animation plays immediately. Health/hunger     ║
 * ║                   visually updated. Rolled back if server sends  ║
 * ║                   an EntityStatusS2CPacket negating the use.    ║
 * ║                                                                 ║
 * ║  Block Placement  Block state set locally, rolled back if the   ║
 * ║                   server sends a BlockUpdateS2CPacket that       ║
 * ║                   contradicts the placement.                    ║
 * ║                                                                 ║
 * ║  Item Switch      Hotbar visual updates instantly. Deferred     ║
 * ║                   reconciliation with server inventory sync.    ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * IMPORTANT: Vanilla already implements some of these (block placement
 * has client-side prediction in ClientPlayerInteractionManager). We
 * augment rather than replace vanilla behaviour — only acting where
 * the stock game desyncs visually under high latency.
 *
 * TODO: Bow draw-state persistence across world reload requires
 *       server-side ActiveItemToClientSyncPacket — not achievable
 *       purely client-side.
 */
public class UtilityPredictionManager {

    private final HitregistryConfig config;
    private final Deque<PendingUtilityAction> pending = new ArrayDeque<>(16);

    // Tracks locally-spawned predictive projectile entity IDs
    // so we can despawn them on rollback.
    private final java.util.HashSet<Integer> predictiveEntityIds = new java.util.HashSet<>();

    public UtilityPredictionManager(HitregistryConfig config) {
        this.config = config;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called by Mixins when the local player initiates a utility action
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by {@link diolez.hitregistry.mixin.ItemUsageMixin} when the
     * player right-clicks with an ender pearl.
     *
     * We queue a pending action and let the mixin spawn a local particle/entity
     * trail immediately. The entity is kept alive until:
     *   a) Server echoes an AddEntityS2CPacket for a real pearl → confirmed
     *   b) Server does NOT echo within rollbackDelayMs → despawn (rejected throw)
     */
    public void onPearlThrow(long clientTick) {
        if (!config.isPearlPredictionEnabled()) return;
        pending.addLast(PendingUtilityAction.forPearl(clientTick));
        logDebug("Predicted ender pearl throw, tick={}", clientTick);
    }

    /**
     * Called by {@link diolez.hitregistry.mixin.ItemUsageMixin} when a bow
     * or crossbow releases a projectile.
     */
    public void onProjectileLaunch(long clientTick) {
        if (!config.isProjectilePredictionEnabled()) return;
        pending.addLast(PendingUtilityAction.forProjectile(clientTick));
        logDebug("Predicted projectile launch, tick={}", clientTick);
    }

    /**
     * Called by {@link diolez.hitregistry.mixin.ItemUsageMixin} when food,
     * potions, or other consumables begin their use animation.
     *
     * @param stackSnapshot Copy of the ItemStack being used (for rollback display)
     */
    public void onItemUseStart(long clientTick, ItemStack stackSnapshot) {
        if (!config.isItemUsePredictionEnabled()) return;
        // We capture the stack as a shallow snapshot — we only need it for
        // visual rollback (restoring the item in hand visually), not for
        // actual inventory manipulation (which is server-authoritative).
        pending.addLast(PendingUtilityAction.forItemUse(clientTick, stackSnapshot.copy()));
        logDebug("Predicted item use start: {}", stackSnapshot.getName().getString());
    }

    /**
     * Called by {@link diolez.hitregistry.mixin.ItemUsageMixin} when a block
     * placement packet is about to be sent.
     *
     * @param pos The block position being placed
     */
    public void onBlockPlace(long clientTick, BlockPos pos) {
        if (!config.isBlockPlacementPredictionEnabled()) return;
        pending.addLast(PendingUtilityAction.forBlockPlace(clientTick, pos.asLong()));
        logDebug("Predicted block placement at {}", pos);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called by Mixins when server responds
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by {@link diolez.hitregistry.mixin.ClientPlayNetworkHandlerMixin}
     * when the server spawns an entity matching a predicted projectile/pearl.
     *
     * @param serverEntityId The entity ID assigned by the server
     */
    public void onServerEntityAdded(int serverEntityId, PendingUtilityAction.Type expectedType) {
        for (PendingUtilityAction action : pending) {
            if (action.type == expectedType && !action.isAcknowledged()) {
                action.acknowledge(true);
                logDebug("Server confirmed {} entity, id={}", expectedType, serverEntityId);
                return;
            }
        }
    }

    /**
     * Called by {@link diolez.hitregistry.mixin.ClientPlayNetworkHandlerMixin}
     * when a BlockUpdateS2CPacket contradicts a predicted block placement.
     *
     * @param posLong The block position (BlockPos.asLong) that was updated
     */
    public void onServerBlockUpdate(long posLong) {
        for (PendingUtilityAction action : pending) {
            if (action.type == PendingUtilityAction.Type.BLOCK_PLACE
                    && action.blockPosLong == posLong
                    && !action.isAcknowledged()) {
                // Server overrode our predicted placement — rollback is
                // handled by vanilla's block update handler, which sets
                // the correct block state. We just mark it acknowledged.
                action.acknowledge(false);
                logDebug("Block placement at {} denied by server", posLong);
                return;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tick — expire stale predictions
    // ─────────────────────────────────────────────────────────────────────────

    public void tick(MinecraftClient client) {
        if (!config.isUtilityPredictionEnabled()) return;

        long rollbackMs = config.getUtilityRollbackDelayMs();

        pending.removeIf(action -> {
            if (action.isAcknowledged()) return true; // Resolved — remove

            if (action.isExpired(rollbackMs)) {
                // Server didn't confirm in time — rollback
                rollback(action, client);
                return true;
            }
            return false;
        });
    }

    /** Perform the appropriate rollback for an expired, unconfirmed action. */
    private void rollback(PendingUtilityAction action, MinecraftClient client) {
        logDebug("Rolling back unconfirmed {} action", action.type);

        switch (action.type) {
            case ENDER_PEARL, PROJECTILE_LAUNCH -> {
                // Despawn any locally-spawned predictive entities.
                // In practice, vanilla's server-authoritative entity sync will
                // handle this — we just ensure our tracked IDs are cleared.
                predictiveEntityIds.forEach(id -> {
                    if (client.world != null) {
                        // Remove the client-side predictive entity
                        client.world.removeEntity(id, net.minecraft.entity.Entity.RemovalReason.DISCARDED);
                    }
                });
                predictiveEntityIds.clear();
            }

            case ITEM_USE -> {
                // Vanilla typically handles this via ScreenHandlerSlotUpdateS2CPacket.
                // We just ensure the animation is cancelled visually.
                if (client.player != null) {
                    client.player.clearActiveItem();
                }
            }

            case BLOCK_PLACE -> {
                // Vanilla block update packet has already (or will) correct
                // the block state. No extra action needed.
            }

            case ITEM_SWITCH -> {
                // If the server rejects a hotbar switch (rare — usually only
                // on strict anti-cheats that throttle slot changes), restore
                // the previous slot.
                if (client.player != null && action.slotSnapshot >= 0) {
                    client.player.getInventory().selectedSlot = action.slotSnapshot;
                }
            }
        }
    }

    /**
     * Called by {@link diolez.hitregistry.mixin.ClientPlayerEntityMixin}
     * when the local player's selected hotbar slot changes between ticks.
     *
     * @param clientTick   Current client tick
     * @param previousSlot Slot index before the switch (for rollback)
     */
    public void onItemSwitch(long clientTick, int previousSlot) {
        if (!config.isUtilityPredictionEnabled()) return;
        pending.addLast(PendingUtilityAction.forItemSwitch(clientTick, previousSlot));
        logDebug("Predicted item switch from slot={}", previousSlot);
    }

    /** Register a locally-spawned predictive entity ID for potential rollback. */
    public void trackPredictiveEntity(int localEntityId) {
        predictiveEntityIds.add(localEntityId);
    }

    public void reset() {
        pending.clear();
        predictiveEntityIds.clear();
    }

    private void logDebug(String fmt, Object... args) {
        if (config.isDebugLogging()) {
            HitregistryClient.LOGGER.debug("[Hitregistry/Utility] " + fmt, args);
        }
    }
}
