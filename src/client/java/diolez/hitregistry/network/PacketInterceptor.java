package diolez.hitregistry.network;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.prediction.CombatPredictionManager;
import diolez.hitregistry.prediction.PendingUtilityAction;
import diolez.hitregistry.prediction.UtilityPredictionManager;

/**
 * PacketInterceptor — central routing table for incoming S2C packets
 * that are relevant to prediction/reconciliation.
 *
 * This class does NOT intercept raw bytes or modify any packets.
 * It is called by Mixins AFTER vanilla has already handled the packet,
 * purely to update prediction state based on what the server said.
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Packet routing (all read-only, post-vanilla handling)              │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  EntityStatusS2CPacket (id=2, entity hurt)                          │
 * │    → CombatPredictionManager.onServerHurtConfirmed()               │
 * │                                                                     │
 * │  EntityAttributesS2CPacket / DataTracker health attr                │
 * │    → CombatPredictionManager.onServerHealthUpdate()                │
 * │                                                                     │
 * │  AddEntityS2CPacket (projectile/pearl)                              │
 * │    → UtilityPredictionManager.onServerEntityAdded()                │
 * │                                                                     │
 * │  BlockUpdateS2CPacket                                               │
 * │    → UtilityPredictionManager.onServerBlockUpdate()                │
 * └─────────────────────────────────────────────────────────────────────┘
 */
public class PacketInterceptor {

    private final CombatPredictionManager combat;
    private final UtilityPredictionManager utility;

    public PacketInterceptor(CombatPredictionManager combat, UtilityPredictionManager utility) {
        this.combat  = combat;
        this.utility = utility;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Combat reconciliation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by {@link diolez.hitregistry.mixin.ClientPlayNetworkHandlerMixin}
     * when EntityStatusS2CPacket is received with status 2 (entity hurt).
     *
     * EntityStatusS2CPacket status codes (vanilla 1.21):
     *   2 = living entity hurt
     *   3 = living entity death
     *   29 = shield block
     */
    public void onEntityStatus(int entityId, byte status, float entityCurrentHealth) {
        if (status == 2) { // HURT
            HitregistryClient.LOGGER.debug(
                "[Hitregistry/Packet] EntityStatus HURT — entity={}, hp={}", entityId, entityCurrentHealth);
            combat.onServerHurtConfirmed(entityId, entityCurrentHealth);
        }
        // Status 3 (death) also implicitly confirms damage — treat as confirmed
        if (status == 3) {
            combat.onServerHurtConfirmed(entityId, 0f);
        }
    }

    /**
     * Called by {@link diolez.hitregistry.mixin.ClientPlayNetworkHandlerMixin}
     * when a health attribute update is received for a tracked entity.
     *
     * In 1.21, entity health sync comes via EntityTrackerUpdateS2CPacket
     * (tracked data) using DataTracker entry with health type.
     * The Mixin extracts the health value and forwards it here.
     *
     * @param entityId       The entity whose health was updated
     * @param newHealth      The server-authoritative health value
     */
    public void onEntityHealthSync(int entityId, float newHealth) {
        HitregistryClient.LOGGER.debug(
            "[Hitregistry/Packet] EntityHealthSync — entity={}, hp={}", entityId, newHealth);
        combat.onServerHealthUpdate(entityId, newHealth);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility reconciliation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when the server spawns a new entity.
     * We check if this matches a pending predicted projectile or pearl.
     *
     * @param entityId      Server-assigned entity ID
     * @param entityTypeKey String representation of EntityType registry key
     */
    public void onEntityAdded(int entityId, String entityTypeKey) {
        // Ender pearl entity type key in 1.21: minecraft:ender_pearl
        if (entityTypeKey.contains("ender_pearl")) {
            utility.onServerEntityAdded(entityId, PendingUtilityAction.Type.ENDER_PEARL);
        }
        // Arrow / spectral arrow / crossbow bolts
        else if (entityTypeKey.contains("arrow") || entityTypeKey.contains("bolt")) {
            utility.onServerEntityAdded(entityId, PendingUtilityAction.Type.PROJECTILE_LAUNCH);
        }
    }

    /**
     * Called when the server sends a block update that may contradict
     * a predicted placement.
     *
     * @param blockPosLong BlockPos.asLong() of the updated position
     */
    public void onBlockUpdate(long blockPosLong) {
        utility.onServerBlockUpdate(blockPosLong);
    }
}
