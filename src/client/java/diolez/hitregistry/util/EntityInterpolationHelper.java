package diolez.hitregistry.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * EntityInterpolationHelper — predicts where an entity will be in N ticks.
 *
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  WHY POSITION PREDICTION?                                           ║
 * ║                                                                     ║
 * ║  Vanilla interpolates entity positions between server ticks using   ║
 * ║  a lerp with 3 ticks of history. This creates a "rubber-band" feel ║
 * ║  where entities visually lag behind their server positions.         ║
 * ║                                                                     ║
 * ║  For hit detection, the key issue is: when the client registers a   ║
 * ║  hit at visual position X, the server evaluates the hit at the     ║
 * ║  entity's authoritative position Y (which may be 1–3 blocks ahead  ║
 * ║  on a high-ping connection).                                        ║
 * ║                                                                     ║
 * ║  This helper projects the entity's position forward by the one-way  ║
 * ║  latency to estimate where the server thinks the entity actually is.║
 * ║                                                                     ║
 * ║  NOTE: This is used for diagnostic/debug purposes. We do NOT feed   ║
 * ║  this projection into actual hit packets — that would constitute    ║
 * ║  server-side position spoofing and trigger Grim/Vulcan bans.       ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */
public final class EntityInterpolationHelper {

    private EntityInterpolationHelper() {}

    /**
     * Estimate the entity's server-authoritative position at the moment
     * our attack packet will arrive, accounting for one-way latency.
     *
     * Uses a constant-velocity extrapolation from the entity's current
     * velocity vector (which is already server-interpolated by vanilla).
     *
     * Accuracy degrades rapidly beyond ~3 ticks (150ms) because entity
     * movement is not purely linear (gravity, knockback, collision).
     *
     * @param entity          The target entity
     * @param oneWayLatencyMs Estimated one-way network latency in ms
     * @return Projected position at the estimated server arrival time
     */
    public static Vec3d projectPosition(Entity entity, long oneWayLatencyMs) {
        // Convert latency to fractional ticks (50ms per tick)
        double latencyTicks = oneWayLatencyMs / 50.0;

        // Clamp projection to 6 ticks (300ms) — beyond this, linear extrapolation
        // is too inaccurate to be useful and could mislead the debug overlay.
        latencyTicks = Math.min(latencyTicks, 6.0);

        Vec3d velocity = entity.getVelocity();
        Vec3d currentPos = entity.getPos();

        // Apply gravity to Y projection for entities affected by gravity.
        // In 1.21, hasNoGravity() returns true for spectators, items with
        // NoGravity tag, etc. Standard player/mob entities have gravity.
        double gravityPerTick = entity.hasNoGravity() ? 0.0 : -0.08 * 0.98; // vanilla gravity × drag

        double projX = currentPos.x + velocity.x * latencyTicks;
        double projY = currentPos.y + velocity.y * latencyTicks
                     + 0.5 * gravityPerTick * latencyTicks * latencyTicks;
        double projZ = currentPos.z + velocity.z * latencyTicks;

        return new Vec3d(projX, projY, projZ);
    }

    /**
     * Returns the displacement (in blocks) between the entity's current
     * visual position and its estimated server-authoritative position.
     *
     * Used by the debug overlay to show how far "off" visual hits are.
     *
     * @param entity          Target entity
     * @param oneWayLatencyMs One-way latency in ms
     * @return Displacement in blocks
     */
    public static double getPositionError(Entity entity, long oneWayLatencyMs) {
        Vec3d current   = entity.getPos();
        Vec3d projected = projectPosition(entity, oneWayLatencyMs);
        return current.distanceTo(projected);
    }

    /**
     * Returns whether the position error is large enough to likely cause
     * a ghost hit (server rejects hit because entity moved past the hitbox).
     *
     * Threshold: 0.5 blocks (half a standard player hitbox width).
     *
     * @param entity          Target entity
     * @param oneWayLatencyMs One-way latency in ms
     */
    public static boolean isPositionErrorSignificant(Entity entity, long oneWayLatencyMs) {
        return getPositionError(entity, oneWayLatencyMs) > 0.5;
    }
}
