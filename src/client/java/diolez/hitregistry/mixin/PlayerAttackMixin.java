package diolez.hitregistry.mixin;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.prediction.CombatPredictionManager;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerAttackMixin — captures the moment the local player initiates an attack.
 *
 * Target: {@link ClientPlayerInteractionManager#attackEntity(PlayerEntity, Entity)}
 *
 * Why this method?
 *   ClientPlayerInteractionManager.attackEntity() is called by
 *   MinecraftClient when the player left-clicks an entity. It is
 *   the single authoritative site where:
 *     a) The vanilla attack cooldown is checked
 *     b) PlayerInteractEntityC2SPacket (attack) is queued
 *     c) Attack animation begins
 *
 *   Injecting at HEAD (before vanilla) gives us the exact moment the
 *   player's intent is registered, before any cooldown rejection.
 *   We then check the same cooldown condition vanilla does to avoid
 *   recording predictions for swings that vanilla will reject anyway.
 *
 * ANTI-CHEAT NOTE:
 *   We inject at HEAD but do NOT cancel. Vanilla processing always
 *   completes. Our prediction is a parallel, visual-only action.
 *   No extra packets are sent.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class PlayerAttackMixin {

    /**
     * Fires immediately before vanilla processes the attack.
     * We forward to the CombatPredictionManager so it can snapshot
     * the target's state and begin the prediction window.
     *
     * @param player The local player (should always be client player)
     * @param target The entity being attacked
     */
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void hitregistry$onAttackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isCombatPredictionEnabled()) return;

        CombatPredictionManager manager = HitregistryClient.getCombat();
        if (manager == null) return;

        // Guard: only predict if attack cooldown is sufficiently charged.
        // Vanilla uses > 0.9f for full damage; we use 0.5f to cover partial hits.
        // Predictions on zero-charge swings would always be ghost hits, creating
        // unnecessary visual noise.
        float cooldown = player.getAttackCooldownProgress(0.5f);
        if (cooldown < 0.1f) {
            // Swing is essentially on full cooldown — server will likely reject
            // or apply near-zero damage. Don't bother predicting.
            return;
        }

        manager.onLocalAttack(player, target);
    }
}
