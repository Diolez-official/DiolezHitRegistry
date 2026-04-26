package diolez.hitregistry.prediction;

/**
 * Describes a client-predicted utility action that is awaiting server confirmation.
 *
 * "Utility" covers everything outside direct combat hits:
 *   - Ender pearl throws
 *   - Crossbow / bow projectile launches
 *   - Food / potion consumption
 *   - Block placement
 *   - Item switching
 *
 * Each action type has different rollback semantics; the {@link UtilityPredictionManager}
 * handles the type-specific logic.
 */
public final class PendingUtilityAction {

    public enum Type {
        ENDER_PEARL,
        PROJECTILE_LAUNCH,   // Bow or crossbow bolt
        ITEM_USE,            // Food, potion, milk bucket, etc.
        BLOCK_PLACE,
        ITEM_SWITCH
    }

    public final Type type;
    public final long clientTick;
    public final long wallTimeMs;

    /** Slot index for ITEM_SWITCH rollback. */
    public final int slotSnapshot;

    /**
     * Block position for BLOCK_PLACE rollback.
     * Packed as long (BlockPos.asLong) to avoid GC pressure.
     */
    public final long blockPosLong;

    /** Item stack NBT snapshot for ITEM_USE rollback (as opaque object). */
    public final Object itemStackSnapshot;

    private boolean acknowledged = false;
    private boolean confirmed    = false;

    // ── Constructors per type ─────────────────────────────────────────────────

    public static PendingUtilityAction forItemUse(long tick, Object stackSnapshot) {
        return new PendingUtilityAction(Type.ITEM_USE, tick, -1, 0L, stackSnapshot);
    }

    public static PendingUtilityAction forBlockPlace(long tick, long blockPosLong) {
        return new PendingUtilityAction(Type.BLOCK_PLACE, tick, -1, blockPosLong, null);
    }

    public static PendingUtilityAction forPearl(long tick) {
        return new PendingUtilityAction(Type.ENDER_PEARL, tick, -1, 0L, null);
    }

    public static PendingUtilityAction forProjectile(long tick) {
        return new PendingUtilityAction(Type.PROJECTILE_LAUNCH, tick, -1, 0L, null);
    }

    public static PendingUtilityAction forItemSwitch(long tick, int previousSlot) {
        return new PendingUtilityAction(Type.ITEM_SWITCH, tick, previousSlot, 0L, null);
    }

    private PendingUtilityAction(Type type, long clientTick, int slot, long blockPosLong, Object itemSnap) {
        this.type              = type;
        this.clientTick        = clientTick;
        this.wallTimeMs        = System.currentTimeMillis();
        this.slotSnapshot      = slot;
        this.blockPosLong      = blockPosLong;
        this.itemStackSnapshot = itemSnap;
    }

    public void acknowledge(boolean ok) {
        this.acknowledged = true;
        this.confirmed    = ok;
    }

    public boolean isAcknowledged() { return acknowledged; }
    public boolean isConfirmed()    { return confirmed; }
    public boolean isExpired(long rollbackDelayMs) {
        return System.currentTimeMillis() - wallTimeMs > rollbackDelayMs;
    }
}
