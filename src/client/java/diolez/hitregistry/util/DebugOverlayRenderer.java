package diolez.hitregistry.util;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.prediction.CombatPredictionManager;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

/**
 * DebugOverlayRenderer — on-screen HUD for prediction diagnostics.
 *
 * Registered via HudRenderCallback (Fabric API) when debugOverlay=true.
 * This avoids modifying the GameRenderer and is the canonical Fabric
 * approach for HUD additions in 1.21.
 *
 * The overlay displays:
 *   Line 1: [Hitregistry] status and feature toggles
 *   Line 2: Estimated RTT and tick-boundary crossing risk
 *   Line 3: Pending prediction count
 *   Line 4: Target entity visual HP vs real HP (when targeting a LivingEntity)
 *   Line 5: Position error estimate (how far off our hit prediction may be)
 *
 * HOW TO REGISTER:
 *   Call DebugOverlayRenderer.register() from HitregistryClient.onInitializeClient()
 *   AFTER checking config.isDebugOverlay(). The HudRenderCallback is persistent
 *   once registered; the rendering guard inside checks the config each frame.
 */
public final class DebugOverlayRenderer {

    private static boolean registered = false;

    private DebugOverlayRenderer() {}

    /**
     * Register the HUD callback. Safe to call multiple times — idempotent.
     */
    public static void register() {
        if (registered) return;
        registered = true;

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            // Guard: only render when debug overlay is enabled in config
            if (HitregistryClient.getConfig() == null) return;
            if (!HitregistryClient.getConfig().isDebugOverlay()) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;

            render(drawContext, client);
        });
    }

    private static void render(DrawContext ctx, MinecraftClient client) {
        TextRenderer font = client.textRenderer;
        CombatPredictionManager combat = HitregistryClient.getCombat();
        if (combat == null) return;

        int x = 4;
        int y = 4;
        int lineH = font.fontHeight + 2;
        int bgColor  = 0x99000000; // Semi-transparent black
        int textColor = 0x00FF88;  // Bright green for overlay readability

        // ── Line 1: Mod status ──────────────────────────────────────────────
        String statusLine = "[Hitregistry] Combat: "
                + (HitregistryClient.getConfig().isCombatPredictionEnabled() ? "ON" : "OFF")
                + " | Utility: "
                + (HitregistryClient.getConfig().isUtilityPredictionEnabled() ? "ON" : "OFF");
        drawLine(ctx, font, statusLine, x, y, bgColor, textColor);
        y += lineH;

        // ── Line 2: RTT and tick alignment ──────────────────────────────────
        float rttTicks = combat.getEstimatedRttTicks();
        int rttMs = (int)(rttTicks * 50f);

        // Estimate one-way latency for tick-boundary risk
        long oneWayMs = rttMs / 2;
        String tickLine = String.format("RTT: ~%dms (%.1f ticks) | %s",
                rttMs, rttTicks,
                TickAlignedHitValidator.debugSummary(oneWayMs));
        int rttColor = rttMs < 80 ? 0x00FF88 : rttMs < 150 ? 0xFFCC00 : 0xFF4444;
        drawLine(ctx, font, tickLine, x, y, bgColor, rttColor);
        y += lineH;

        // ── Line 3: Target entity info ──────────────────────────────────────
        Entity targeted = client.targetedEntity;
        if (targeted instanceof LivingEntity living) {
            float visualHp = combat.getVisualHealth(living);
            float realHp   = living.getHealth();
            float maxHp    = living.getMaxHealth();
            boolean pending = combat.hasPendingHit(living);

            String hpLine = String.format("Target: %s | Visual HP: %.1f / Real HP: %.1f / Max: %.1f%s",
                    living.getName().getString(), visualHp, realHp, maxHp,
                    pending ? " [PENDING]" : "");
            int hpColor = pending ? 0xFFAA00 : 0x00FF88;
            drawLine(ctx, font, hpLine, x, y, bgColor, hpColor);
            y += lineH;

            // ── Line 4: Position error ──────────────────────────────────────
            double posError = EntityInterpolationHelper.getPositionError(living, oneWayMs);
            boolean significant = EntityInterpolationHelper.isPositionErrorSignificant(living, oneWayMs);
            String posLine = String.format("Position error: %.3f blocks%s",
                    posError, significant ? " ⚠ GHOST RISK" : "");
            int posColor = significant ? 0xFF4444 : 0x00FF88;
            drawLine(ctx, font, posLine, x, y, bgColor, posColor);
            y += lineH;
        } else {
            drawLine(ctx, font, "Target: none", x, y, bgColor, 0x888888);
            y += lineH;
        }

        // ── Line 5: Tick clock ──────────────────────────────────────────────
        String tickClockLine = "Client tick: " + TickClock.current();
        drawLine(ctx, font, tickClockLine, x, y, bgColor, 0x888888);
    }

    /**
     * Draw a single line of text with a semi-transparent background rect.
     */
    private static void drawLine(DrawContext ctx, TextRenderer font,
                                  String text, int x, int y,
                                  int bgColor, int textColor) {
        int w = font.getWidth(text);
        // Background rectangle for readability
        ctx.fill(x - 1, y - 1, x + w + 1, y + font.fontHeight + 1, bgColor);
        ctx.drawText(font, text, x, y, textColor, false);
    }
}
