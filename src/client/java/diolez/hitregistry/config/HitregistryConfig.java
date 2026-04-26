package diolez.hitregistry.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import diolez.hitregistry.HitregistryClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;

/**
 * Lightweight JSON config stored in <config>/hitregistry.json.
 *
 * No external config-API dependency — plain Gson keeps the mod lean
 * and compatible with any Fabric environment.
 *
 * All fields intentionally have conservative defaults so the mod is
 * safe out-of-the-box on vanilla and anti-cheat-protected servers.
 */
public class HitregistryConfig {

    // ── Master toggles ─────────────────────────────────────────────────────────

    /** Master switch. Disabling this makes the mod completely inert. */
    private boolean enabled = true;

    /** Enable client-side combat hit prediction + server reconciliation. */
    private boolean combatPredictionEnabled = true;

    /**
     * Enable utility desync fixes (pearls, potions, food, bows, crossbows,
     * block placement, item switching).
     */
    private boolean utilityPredictionEnabled = true;

    // ── Combat prediction tuning ───────────────────────────────────────────────

    /**
     * Maximum age (in client ticks) a pending hit record is kept before
     * it is discarded as a confirmed ghost hit.
     * Lower = snappier rollback. Higher = more tolerant of lag spikes.
     * Range: 5–40. Default: 20 (≈1 second at 20 TPS).
     */
    private int hitExpiryTicks = 20;

    /**
     * Extra hit-window padding in ticks added to account for one-way
     * network latency. Increase on high-ping servers (150+ ms).
     * Range: 0–10. Default: 2.
     */
    private int latencyCompensationTicks = 2;

    /**
     * How aggressively visual damage is predicted before server confirmation.
     * 0.0 = no visual prediction (vanilla behaviour).
     * 1.0 = full immediate visual feedback.
     * Fractional values blend between the two.
     */
    private float predictionStrength = 0.85f;

    /**
     * Lerp speed for rolling back a ghost hit (entity health bar snapping
     * back after a miss is confirmed by server). Higher = faster snap.
     * Range: 0.05–1.0. Default: 0.3.
     */
    private float rollbackLerpSpeed = 0.3f;

    // ── Utility prediction tuning ──────────────────────────────────────────────

    /** Show the ender pearl arc immediately on throw without waiting for server. */
    private boolean pearlPredictionEnabled = true;

    /** Predict crossbow/bow shot trajectory locally. */
    private boolean projectilePredictionEnabled = true;

    /** Immediately consume food/potion visually before server confirmation. */
    private boolean itemUsePredictionEnabled = true;

    /** Predict block placement visually before server ack. */
    private boolean blockPlacementPredictionEnabled = true;

    /**
     * Delay (ms) before rolling back a predicted utility action if the server
     * hasn't confirmed it. Increase for laggy servers.
     * Range: 100–1000. Default: 400.
     */
    private int utilityRollbackDelayMs = 400;

    // ── Debug ──────────────────────────────────────────────────────────────────

    /** Log prediction events to console. Noisy — only enable for debugging. */
    private boolean debugLogging = false;

    /** Render hit-prediction debug overlay on-screen. */
    private boolean debugOverlay = false;

    // ── Serialisation ──────────────────────────────────────────────────────────

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "hitregistry.json";

    public static HitregistryConfig loadOrCreate() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        File configFile = configPath.toFile();

        if (configFile.exists()) {
            try (Reader reader = new FileReader(configFile)) {
                HitregistryConfig cfg = GSON.fromJson(reader, HitregistryConfig.class);
                if (cfg != null) {
                    HitregistryClient.LOGGER.info("[Hitregistry] Loaded config from {}", configFile);
                    cfg.save(); // Re-save to add any new fields from an update
                    return cfg;
                }
            } catch (Exception e) {
                HitregistryClient.LOGGER.warn("[Hitregistry] Failed to read config, using defaults: {}", e.getMessage());
            }
        }

        HitregistryConfig defaults = new HitregistryConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        try (Writer writer = new FileWriter(configPath.toFile())) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            HitregistryClient.LOGGER.warn("[Hitregistry] Failed to save config: {}", e.getMessage());
        }
    }

    // ── Getters (no public setters — config is loaded once, changed via file) ──

    public boolean isEnabled()                        { return enabled; }
    public boolean isCombatPredictionEnabled()        { return enabled && combatPredictionEnabled; }
    public boolean isUtilityPredictionEnabled()       { return enabled && utilityPredictionEnabled; }
    public int     getHitExpiryTicks()                { return Math.max(5, Math.min(40, hitExpiryTicks)); }
    public int     getLatencyCompensationTicks()      { return Math.max(0, Math.min(10, latencyCompensationTicks)); }
    public float   getPredictionStrength()            { return Math.max(0f, Math.min(1f, predictionStrength)); }
    public float   getRollbackLerpSpeed()             { return Math.max(0.05f, Math.min(1f, rollbackLerpSpeed)); }
    public boolean isPearlPredictionEnabled()         { return isUtilityPredictionEnabled() && pearlPredictionEnabled; }
    public boolean isProjectilePredictionEnabled()    { return isUtilityPredictionEnabled() && projectilePredictionEnabled; }
    public boolean isItemUsePredictionEnabled()       { return isUtilityPredictionEnabled() && itemUsePredictionEnabled; }
    public boolean isBlockPlacementPredictionEnabled(){ return isUtilityPredictionEnabled() && blockPlacementPredictionEnabled; }
    public int     getUtilityRollbackDelayMs()        { return Math.max(100, Math.min(1000, utilityRollbackDelayMs)); }
    public boolean isDebugLogging()                   { return debugLogging; }
    public boolean isDebugOverlay()                   { return debugOverlay; }
}
