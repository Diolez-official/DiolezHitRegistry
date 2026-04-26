package diolez.hitregistry;

import diolez.hitregistry.config.HitregistryConfig;
import diolez.hitregistry.prediction.CombatPredictionManager;
import diolez.hitregistry.prediction.UtilityPredictionManager;
import diolez.hitregistry.network.PacketInterceptor;
import diolez.hitregistry.util.DebugOverlayRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DiolezHitregistry — Client entrypoint.
 *
 * Lifecycle:
 *   1. Config loads (or creates defaults) from disk.
 *   2. Prediction managers initialise their state queues.
 *   3. Per-tick hooks are registered to drive reconciliation.
 *   4. Disconnect hook clears all pending prediction state to prevent leaks.
 *
 * Everything here is PURELY client-side visual / prediction logic.
 * No authoritative server state is modified or spoofed.
 */
public class HitregistryClient implements ClientModInitializer {

    public static final String MOD_ID = "hitregistry";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Singletons — accessed by Mixins via static getters
    private static HitregistryConfig config;
    private static CombatPredictionManager combatManager;
    private static UtilityPredictionManager utilityManager;
    private static PacketInterceptor packetInterceptor;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Hitregistry] Initialising DiolezHitregistry v1.0.0");

        // 1. Load persistent config
        config = HitregistryConfig.loadOrCreate();
        LOGGER.info("[Hitregistry] Config loaded — combat prediction: {}, utility prediction: {}",
                config.isCombatPredictionEnabled(), config.isUtilityPredictionEnabled());

        // 2. Initialise prediction subsystems
        combatManager   = new CombatPredictionManager(config);
        utilityManager  = new UtilityPredictionManager(config);
        packetInterceptor = new PacketInterceptor(combatManager, utilityManager);

        // 3. Tick hook — drives reconciliation every client tick (~50 ms at 20 TPS)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;
            combatManager.tick(client);
            utilityManager.tick(client);
        });

        // 4. Register HUD debug overlay (always registered, guarded internally by config flag)
        DebugOverlayRenderer.register();

        // 5. Cleanup on server disconnect to prevent cross-session state bleed
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("[Hitregistry] Disconnected — flushing prediction state");
            combatManager.reset();
            utilityManager.reset();
        });

        LOGGER.info("[Hitregistry] Ready.");
    }

    // ── Static accessors used by Mixins ────────────────────────────────────────

    public static HitregistryConfig getConfig()           { return config; }
    public static CombatPredictionManager getCombat()     { return combatManager; }
    public static UtilityPredictionManager getUtility()   { return utilityManager; }
    public static PacketInterceptor getPacketInterceptor(){ return packetInterceptor; }
}
