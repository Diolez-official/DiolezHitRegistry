package diolez.hitregistry.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * TickClock — monotonic client-tick counter.
 *
 * Provides a global tick counter incremented each client tick.
 * Used to timestamp prediction records so we can compute their age
 * without relying on wall-clock time (which can drift under GC pauses).
 *
 * Registered via ClientTickEvents in HitregistryClient.
 * Can also be called by Mixins directly.
 */
public final class TickClock {

    private static long tick = 0L;

    static {
        // Register the per-tick increment
        ClientTickEvents.START_CLIENT_TICK.register(client -> tick++);
    }

    private TickClock() {}

    /** Returns the current client tick count. */
    public static long current() { return tick; }

    /** Reset on disconnect to prevent counter overflow across long sessions. */
    public static void reset() { tick = 0L; }
}
