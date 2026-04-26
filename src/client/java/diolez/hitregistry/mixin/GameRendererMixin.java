package diolez.hitregistry.mixin;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.prediction.CombatPredictionManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRendererMixin — hooks into the render loop for two purposes:
 *
 * 1. DEBUG OVERLAY (when debugOverlay=true in config):
 *    Renders a small HUD showing pending predictions, RTT estimate,
 *    and rollback events. Useful during mod development/testing.
 *
 * 2. VISUAL HEALTH INTERCEPTION (future):
 *    TODO: In a future version, hook into the entity health bar rendering
 *    (via InGameHud or EntityRenderer) to display the predicted health value
 *    rather than the authoritative health value during the prediction window.
 *    This requires a separate mixin on EntityRenderer or InGameHud.
 *    Currently, the visual health override in CombatPredictionManager is
 *    computed but not wired into the render pipeline.
 *
 * NOTE: The overlay rendering in this mixin uses a very lightweight approach
 * (logging to screen via DrawContext) rather than importing heavyweight
 * HUD libraries. For a production release, consider using the Fabric
 * HUD API or a dedicated screen overlay class.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow
    private MinecraftClient client;

    /**
     * Post-render hook to draw the debug overlay if enabled.
     * Runs at the end of each frame, after the world and HUD are drawn.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void hitregistry$onRenderTail(
            net.minecraft.client.render.RenderTickCounter tickCounter,
            boolean tick, CallbackInfo ci) {

        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isDebugOverlay()) return;
        if (client.player == null) return;

        CombatPredictionManager combat = HitregistryClient.getCombat();
        if (combat == null) return;

        // ── Debug overlay rendering ────────────────────────────────────────────
        // Draw text overlay using DrawContext via InGameHud.
        // Text is rendered top-left in green so it's visible against any background.
        //
        // TODO: Replace this with a proper DrawContext-based render pass.
        //       In 1.21, the render method signature changed — verify the exact
        //       RenderTickCounter parameter type against the current Yarn mappings.
        //
        // For now, we use LOGGER to output prediction state to the console
        // as a lightweight debugging tool.

        if (HitregistryClient.getConfig().isDebugLogging()) {
            Entity target = client.targetedEntity;
            if (target instanceof LivingEntity living) {
                float visualHp = combat.getVisualHealth(living);
                float realHp   = living.getHealth();
                if (Math.abs(visualHp - realHp) > 0.1f) {
                    HitregistryClient.LOGGER.debug(
                        "[Hitregistry/Render] Visual HP override on {} → {:.2f} (real: {:.2f})",
                        target.getName().getString(), visualHp, realHp);
                }
            }
        }
    }
}
