package diolez.hitregistry.prediction;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.config.HitregistryConfig;
import diolez.hitregistry.util.DamageEstimator;
import diolez.hitregistry.util.TickClock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * CombatPredictionManager — the heart of ghost-hit elimination.
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  PREDICTION FLOW                                                ║
 * ║                                                                 ║
 * ║  Client swings → onLocalAttack()                                ║
 * ║      └─ PendingHit created, damage estimated, visual applied    ║
 * ║                                                                 ║
 * ║  Server sends EntityStatusS2CPacket (hurt event)                ║
 * ║      └─ onServerHurtConfirmed() → marks hit CONFIRMED           ║
 * ║                                                                 ║
 * ║  OR server sends HealthUpdateS2CPacket with unchanged HP         ║
 * ║      └─ onServerHealthUpdate() → marks hit GHOST → rollback()   ║
 * ║                                                                 ║
 * ║  Expiry: if no ack within hitExpiryTicks → treat as ghost       ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * ANTI-CHEAT NOTES:
 *   - We never send any extra attack packets. The game's normal
 *     PlayerInteractEntityC2SPacket is sent exactly once per swing.
 *   - All health changes are purely visual (rendered health bar,
 *     hurt overlay tint). The authoritative health value on the
 *     Entity object is restored on rollback.
 *   - Timing windows are conservative to avoid triggering Grim's
 *     hit-rate limiters.
 */
public class CombatPredictionManager {

    private final HitregistryConfig config;

    /**
     * Ring-buffer of pending (unacknowledged) hits.
     * Max capacity matches hitExpiryTicks — older entries are expired.
     */
    private final Deque<PendingHit> pending = new ArrayDeque<>(32);

    /**
     * Rolling average of round-trip latency in ticks, used to dynamically
     * calibrate the expiry window instead of relying on a fixed constant.
     *
     * TODO: wire this to actual network ping from NetworkHandler.connection
     *       once the accessor mixin is confirmed stable.
     */
    private float estimatedRttTicks = 4f;

    /**
     * Visual health override — a float per entity ID that overrides the
     * rendered health value during the prediction window.
     * Key: entity ID, Value: predicted health value.
     * Using a primitive map would be marginally faster; Deque is fine for
     * typical PvP entity counts (< 20 in render range).
     */
    private final java.util.HashMap<Integer, Float> visualHealthOverrides = new java.util.HashMap<>();

    public CombatPredictionManager(HitregistryConfig config) {
        this.config = config;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API (called by Mixins)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by {@link diolez.hitregistry.mixin.PlayerAttackMixin} immediately
     * before the game sends the attack packet to the server.
     *
     * We estimate damage and apply a visual-only health reduction so the hit
     * feels instantaneous to the player.
     *
     * @param attacker The local player
     * @param target   The entity being struck
     */
    public void onLocalAttack(PlayerEntity attacker, Entity target) {
        if (!config.isCombatPredictionEnabled()) return;
        if (!(target instanceof LivingEntity living)) return;

        // Estimate damage using local game state (weapon, enchants, strength effect)
        float predictedDmg = DamageEstimator.estimate(attacker, living);

        // Clamp to current health — we can't predict more damage than the entity has
        float currentVisualHealth = visualHealthOverrides.getOrDefault(target.getId(), living.getHealth());
        float newVisualHealth = Math.max(0f, currentVisualHealth - predictedDmg * config.getPredictionStrength());

        // Store the prediction
        PendingHit hit = new PendingHit(TickClock.current(), target, predictedDmg);
        pending.addLast(hit);

        // Apply VISUAL-ONLY health override (does not touch Entity.health field)
        visualHealthOverrides.put(target.getId(), newVisualHealth);

        if (config.isDebugLogging()) {
            HitregistryClient.LOGGER.debug(
                "[Hitregistry] Predicted hit on {} — dmg={:.2f}, visualHP={:.2f}→{:.2f}, pending={}",
                target.getName().getString(), predictedDmg,
                currentVisualHealth, newVisualHealth, pending.size());
        }
    }

    /**
     * Called by {@link diolez.hitregistry.mixin.ClientPlayNetworkHandlerMixin}
     * when an EntityStatusS2CPacket with HURT status (id=2) is received for
     * an entity we have a pending prediction for.
     *
     * This is the "happy path" — server confirms the hit.
     *
     * @param entityId  The server entity ID that was hurt
     * @param newHealth The authoritative health value from the server
     */
    public void onServerHurtConfirmed(int entityId, float newHealth) {
        if (!config.isCombatPredictionEnabled()) return;

        for (PendingHit hit : pending) {
            if (hit.target.getId() == entityId && !hit.isAcknowledged()) {
                hit.acknowledge(true);

                // Replace our predicted visual health with the authoritative value.
                // The lerp toward real health happens in tick().
                visualHealthOverrides.put(entityId, newHealth);

                if (config.isDebugLogging()) {
                    HitregistryClient.LOGGER.debug(
                        "[Hitregistry] Hit confirmed by server — entity={}, serverHP={:.2f}", entityId, newHealth);
                }
                return; // Only reconcile the oldest matching pending hit
            }
        }
    }

    /**
     * Called by {@link diolez.hitregistry.mixin.ClientPlayNetworkHandlerMixin}
     * when a HealthUpdateS2CPacket arrives for the TARGET entity (not the player),
     * or when entity health is synchronised with no change — indicating a miss.
     *
     * In vanilla, targets don't normally send HealthUpdateS2CPackets (those are
     * player-only). Instead, we detect a "miss" when EntityAttributesS2CPacket or
     * an EntityTrackerUpdate arrives showing the health value unchanged.
     *
     * TODO: In 1.21, entity attribute sync via EntityAttributesS2CPacket
     *       is the primary mechanism. The mixin target should be
     *       ClientPlayNetworkHandler#onEntityAttributes for robustness.
     *
     * @param entityId          The entity whose health the server just reported
     * @param authoritative HP  The health the server says the entity actually has
     */
    public void onServerHealthUpdate(int entityId, float authoritativeHp) {
        if (!config.isCombatPredictionEnabled()) return;

        for (PendingHit hit : pending) {
            if (hit.target.getId() == entityId && !hit.isAcknowledged()) {
                // If the server health is within 0.5 of our snapshot, the hit didn't land
                boolean ghost = Math.abs(authoritativeHp - hit.targetHealthSnapshot) < 0.5f;
                hit.acknowledge(!ghost);

                if (ghost) {
                    // Rollback: restore visual health to authoritative value
                    visualHealthOverrides.put(entityId, authoritativeHp);
                    if (config.isDebugLogging()) {
                        HitregistryClient.LOGGER.debug(
                            "[Hitregistry] GHOST HIT detected — rolling back entity={}, HP restored to {:.2f}",
                            entityId, authoritativeHp);
                    }
                }
                return;
            }
        }
    }

    /**
     * Per-tick update:
     *   1. Lerp visual health overrides toward entity's real health over time
     *      (creates a smooth snap-back on rollback).
     *   2. Expire stale pending hits that were never acknowledged.
     *   3. Update RTT estimate from current ping.
     */
    public void tick(MinecraftClient client) {
        if (!config.isCombatPredictionEnabled()) return;
        if (client.world == null) return;

        long now = TickClock.current();
        int expiryWindow = config.getHitExpiryTicks() + config.getLatencyCompensationTicks();

        // Update rolling RTT estimate from measured ping
        // (client.getNetworkHandler().getConnection() exposes latency in ms)
        try {
            if (client.getNetworkHandler() != null) {
                // Ping in ms → convert to ticks (20 TPS = 50ms/tick)
                // We use one-way latency ≈ RTT/2 for the compensation window
                float pingTicks = (client.getNetworkHandler().getPlayerListEntry(client.player.getUuid()) != null
                        ? client.getNetworkHandler().getPlayerListEntry(client.player.getUuid()).getLatency()
                        : 100) / 50f;
                // Smooth the estimate with exponential moving average (α=0.1)
                estimatedRttTicks = estimatedRttTicks * 0.9f + pingTicks * 0.1f;
            }
        } catch (Exception ignored) { /* PlayerListEntry may be null briefly */ }

        // Lerp visual health overrides toward authoritative health
        Iterator<java.util.Map.Entry<Integer, Float>> it = visualHealthOverrides.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            int entityId = entry.getKey();
            float visualHp = entry.getValue();

            Entity entity = client.world.getEntityById(entityId);
            if (entity == null) {
                it.remove(); // Entity left render range — discard override
                continue;
            }

            float realHp = (entity instanceof LivingEntity le) ? le.getHealth() : 0f;
            float lerped = visualHp + (realHp - visualHp) * config.getRollbackLerpSpeed();

            if (Math.abs(lerped - realHp) < 0.05f) {
                // Close enough — remove override and let vanilla rendering take over
                it.remove();
            } else {
                entry.setValue(lerped);
            }
        }

        // Expire unacknowledged hits beyond our tolerance window
        pending.removeIf(hit -> {
            long age = now - hit.clientTick;
            if (age > expiryWindow) {
                if (!hit.isAcknowledged()) {
                    // Treat expired, unconfirmed hits as ghost hits
                    // Restore the visual override to the entity's real health
                    if (client.world != null) {
                        Entity e = client.world.getEntityById(hit.target.getId());
                        if (e instanceof LivingEntity le) {
                            visualHealthOverrides.put(e.getId(), le.getHealth());
                        }
                    }
                    if (config.isDebugLogging()) {
                        HitregistryClient.LOGGER.debug(
                            "[Hitregistry] Hit expired (no server ack within {} ticks) — treating as ghost",
                            expiryWindow);
                    }
                }
                return true; // Remove from queue
            }
            return false;
        });
    }

    /**
     * Returns the visual (predicted) health for an entity if we have an
     * active override, or the real health if not.
     * Called by {@link diolez.hitregistry.mixin.GameRendererMixin} to
     * draw health bars with prediction applied.
     */
    public float getVisualHealth(LivingEntity entity) {
        return visualHealthOverrides.getOrDefault(entity.getId(), entity.getHealth());
    }

    /**
     * Returns true if we currently have an unresolved predicted hit
     * on the given entity — used by the debug overlay.
     */
    public boolean hasPendingHit(Entity entity) {
        return pending.stream().anyMatch(h -> h.target.getId() == entity.getId() && !h.isAcknowledged());
    }

    /**
     * Estimated one-way latency in ticks (RTT / 2), dynamically calibrated.
     */
    public float getEstimatedRttTicks() { return estimatedRttTicks; }

    /** Clear all state on disconnect. */
    public void reset() {
        pending.clear();
        visualHealthOverrides.clear();
        estimatedRttTicks = 4f;
    }
}
