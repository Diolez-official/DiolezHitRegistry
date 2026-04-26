package diolez.hitregistry.util;

/**
 * TickAlignedHitValidator — aligns client-side hit validation windows
 * with the server's 20 TPS tick rate.
 *
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  THE CORE DESYNC PROBLEM                                            ║
 * ║                                                                     ║
 * ║  The server processes hits in 50ms tick windows. If a hit packet    ║
 * ║  arrives at the server 1ms after a tick boundary, it's processed   ║
 * ║  in the NEXT tick. The entity might have moved or been hit by       ║
 * ║  another player in that gap, causing the server to reject our hit.  ║
 * ║                                                                     ║
 * ║  The client renders at 60-300+ FPS but sends packets on its own    ║
 * ║  schedule. The server sees our attack at a different phase of its   ║
 * ║  50ms tick window depending on our ping jitter.                     ║
 * ║                                                                     ║
 * ║  WHAT WE CAN DO CLIENT-SIDE:                                        ║
 * ║  Track where we are in the estimated server tick cycle and adjust   ║
 * ║  our prediction window accordingly. Hits sent just before a tick   ║
 * ║  boundary are at higher risk of being processed in the next tick.  ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */
public final class TickAlignedHitValidator {

    /** Server tick period in milliseconds. */
    public static final long SERVER_TICK_MS = 50L;

    private TickAlignedHitValidator() {}

    /**
     * Estimates the risk that a hit sent RIGHT NOW will be processed
     * in a different server tick than intended, due to timing.
     *
     * @param estimatedOneWayLatencyMs One-way latency (ping / 2) in ms
     * @return A risk score from 0.0 (safe) to 1.0 (likely boundary crossing)
     */
    public static float computeTickBoundaryCrossingRisk(long estimatedOneWayLatencyMs) {
        // Estimate when our packet will arrive at the server (wall time + latency)
        long arrivalMs = System.currentTimeMillis() + estimatedOneWayLatencyMs;

        // Phase within the 50ms server tick window at estimated arrival time.
        // Server ticks are not directly observable client-side; we estimate
        // based on the server's steady 20 TPS schedule.
        long phaseMs = arrivalMs % SERVER_TICK_MS;

        // Risk is highest when our packet arrives in the last 10ms of a server tick
        // (the packet will be processed next tick due to server processing overhead).
        // Risk zone: phase 40–50ms (last 20% of the tick window).
        if (phaseMs >= 40) {
            return (phaseMs - 40f) / 10f; // 0.0 at 40ms, 1.0 at 50ms
        }
        return 0f;
    }

    /**
     * Compute an additional prediction tolerance window (in ticks) based on
     * tick-boundary risk.
     *
     * If we're at high risk of a tick-boundary crossing, we extend the
     * prediction window by 1 tick to avoid premature ghost-hit classification.
     *
     * @param estimatedOneWayLatencyMs One-way latency in ms
     * @return Extra ticks to add to the expiry window (0 or 1)
     */
    public static int getExtraToleranceTicks(long estimatedOneWayLatencyMs) {
        float risk = computeTickBoundaryCrossingRisk(estimatedOneWayLatencyMs);
        return risk > 0.6f ? 1 : 0;
    }

    /**
     * Returns a human-readable summary of the current tick alignment status.
     * Used by the debug overlay.
     *
     * Example: "Phase: 23ms/50ms | Risk: LOW | +0 ticks"
     */
    public static String debugSummary(long estimatedOneWayLatencyMs) {
        long phaseMs = (System.currentTimeMillis() + estimatedOneWayLatencyMs) % SERVER_TICK_MS;
        float risk = computeTickBoundaryCrossingRisk(estimatedOneWayLatencyMs);
        int extra = getExtraToleranceTicks(estimatedOneWayLatencyMs);
        String riskLabel = risk < 0.3f ? "LOW" : risk < 0.7f ? "MED" : "HIGH";
        return String.format("Phase: %dms/%dms | Risk: %s | +%d ticks", phaseMs, SERVER_TICK_MS, riskLabel, extra);
    }
}
