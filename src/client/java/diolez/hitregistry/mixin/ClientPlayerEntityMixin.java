package diolez.hitregistry.mixin;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.prediction.UtilityPredictionManager;
import diolez.hitregistry.util.TickClock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ClientPlayerEntityMixin — detects hotbar slot switches for utility prediction.
 *
 * Target: {@link ClientPlayerEntity#tick()}
 *
 * We track selectedSlot changes between ticks to detect item switches.
 * This is preferable to injecting into setSelectedSlot() because some
 * slot changes (e.g. from scroll wheel) bypass that method in 1.21.
 *
 * WHY TRACK SLOT SWITCHES?
 *   On high-ping servers, switching to a utility item (water bucket,
 *   ender pearl, etc.) and immediately using it can desync because the
 *   server hasn't acknowledged the slot change before the use packet arrives.
 *   We predict the switch and apply a small grace delay before flagging as desync.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

    @Shadow
    public abstract PlayerInventory getInventory();

    /** Previous tick's selected slot — used to detect changes. */
    private int hitregistry$prevSlot = -1;

    @Inject(method = "tick", at = @At("TAIL"))
    private void hitregistry$tickSlotTracking(CallbackInfo ci) {
        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isUtilityPredictionEnabled()) return;

        UtilityPredictionManager utility = HitregistryClient.getUtility();
        if (utility == null) return;

        int currentSlot = getInventory().selectedSlot;

        if (hitregistry$prevSlot >= 0 && currentSlot != hitregistry$prevSlot) {
            // Slot changed this tick — record the switch with previous slot for rollback
            utility.onItemSwitch(TickClock.current(), hitregistry$prevSlot);
        }

        hitregistry$prevSlot = currentSlot;
    }
}
