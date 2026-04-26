package diolez.hitregistry.prediction;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

/**
 * Immutable snapshot of a client-predicted hit.
 */
public final class PendingHit {

    public final long clientTick;
    public final long wallTimeMs;
    public final float predictedDamage;
    public final Entity target;
    public final float targetHealthSnapshot;
    public final double snapX, snapY, snapZ;

    private boolean acknowledged = false;
    private boolean confirmed = false;

    public PendingHit(long clientTick, Entity target, float predictedDamage) {
        this.clientTick = clientTick;
        this.wallTimeMs = System.currentTimeMillis();
        this.target = target;
        this.predictedDamage = predictedDamage;

        // ✅ FIX: Entity does not have getHealth() anymore
        if (target instanceof LivingEntity living) {
            this.targetHealthSnapshot = living.getHealth();
        } else {
            this.targetHealthSnapshot = -1.0f; // fallback for non-living entities
        }

        this.snapX = target.getX();
        this.snapY = target.getY();
        this.snapZ = target.getZ();
    }

    public void acknowledge(boolean serverConfirmedHit) {
        this.acknowledged = true;
        this.confirmed = serverConfirmedHit;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Distance moved since snapshot.
     */
    public double displacementFromSnapshot(Entity e) {
        double dx = e.getX() - snapX;
        double dy = e.getY() - snapY;
        double dz = e.getZ() - snapZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}