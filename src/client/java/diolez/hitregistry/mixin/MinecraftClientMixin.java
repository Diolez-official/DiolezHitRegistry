package diolez.hitregistry.mixin;

import diolez.hitregistry.HitregistryClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MinecraftClientMixin — hooks into the main game tick loop.
 *
 * Target: {@link MinecraftClient#tick()}
 *
 * WHY THIS MIXIN?
 * The Fabric ClientTickEvents fire at the start and end of the tick
 * and are sufficient for most purposes. However, we also inject here
 * as a secondary hook to handle cases where the prediction managers
 * need to run BEFORE the game processes incoming packets in the same tick.
 *
 * In 1.21's tick ordering:
 *   1. MinecraftClient.tick() begins
 *   2. NetworkHandler.tick() drains the packet queue (S2C packets arrive)
 *   3. World/entity tick
 *   4. ClientTickEvents.END fires
 *
 * Our prediction managers need to:
 *   - Record predictions BEFORE step 2 (so we capture state before server overrides)
 *   - Reconcile AFTER step 2 (so we see the server's response in the same frame)
 *
 * The CombatPredictionManager.tick() in ClientTickEvents.END satisfies
 * the "after packet processing" requirement. This mixin handles pre-tick
 * snapshot logic if needed in the future.
 *
 * CURRENT USE: Tick-aligned debug output and future pre-tick snapshot hooks.
 * The actual prediction/reconciliation is driven by ClientTickEvents in
 * HitregistryClient to keep the logic close to the mod entrypoint.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    /**
     * Pre-tick hook — fires at the very start of each game tick,
     * before the network handler processes incoming packets.
     *
     * Use for: capturing world state snapshots, resetting per-tick
     * prediction flags, and future pre-tick instrumentation.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void hitregistry$onTickStart(CallbackInfo ci) {
        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isEnabled()) return;

        // Pre-tick: reset any per-tick transient flags here.
        // Currently a no-op placeholder — kept for future use.
        //
        // TODO: If the prediction manager needs a "did we attack this tick"
        //       flag to avoid double-predictions, reset it here.
    }

    /**
     * Post-tick hook — fires at the end of each game tick, after all
     * entity updates and packet handling for this tick are complete.
     *
     * This is equivalent to ClientTickEvents.END but guaranteed to run
     * even if Fabric events are interrupted by an exception in another
     * listener. Defence-in-depth for prediction state cleanup.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hitregistry$onTickEnd(CallbackInfo ci) {
        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isDebugLogging()) return;

        // Per-tick debug logging (only when debug is explicitly on)
        var combat = HitregistryClient.getCombat();
        if (combat != null) {
            float rtt = combat.getEstimatedRttTicks();
            if (rtt > 8f) { // Log a warning if latency is unusually high
                HitregistryClient.LOGGER.debug(
                    "[Hitregistry] High latency detected: RTT ~{:.1f} ticks (~{}ms) — " +
                    "consider increasing hitExpiryTicks in config", rtt, (int)(rtt * 50));
            }
        }
    }
}
