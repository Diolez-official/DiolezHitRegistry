package diolez.hitregistry.mixin;

import diolez.hitregistry.HitregistryClient;
import diolez.hitregistry.network.PacketInterceptor;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onEntityStatus", at = @At("RETURN"))
    private void hitregistry$onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isCombatPredictionEnabled()) return;

        PacketInterceptor interceptor = HitregistryClient.getPacketInterceptor();
        if (interceptor == null) return;

        try {
            var handler = (ClientPlayNetworkHandler)(Object)this;
            var world = handler.getWorld();
            if (world == null) return;

            Entity entity = packet.getEntity(world);
            if (!(entity instanceof LivingEntity living)) return;

            byte status = packet.getStatus();
            interceptor.onEntityStatus(entity.getId(), status, living.getHealth());

        } catch (Exception e) {
            HitregistryClient.LOGGER.debug("[Hitregistry] EntityStatus intercept error: {}", e.getMessage());
        }
    }

    @Inject(method = "onEntityTrackerUpdate", at = @At("RETURN"))
    private void hitregistry$onEntityTrackerUpdate(EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isCombatPredictionEnabled()) return;

        PacketInterceptor interceptor = HitregistryClient.getPacketInterceptor();
        if (interceptor == null) return;

        try {
            var handler = (ClientPlayNetworkHandler)(Object)this;
            var world = handler.getWorld();
            if (world == null) return;

            Entity entity = world.getEntityById(packet.id());
            if (!(entity instanceof LivingEntity living)) return;

            interceptor.onEntityHealthSync(entity.getId(), living.getHealth());

        } catch (Exception e) {
            HitregistryClient.LOGGER.debug("[Hitregistry] EntityTrackerUpdate intercept error: {}", e.getMessage());
        }
    }

    @Inject(method = "onEntitySpawn", at = @At("RETURN"))
    private void hitregistry$onEntitySpawn(EntitySpawnS2CPacket packet, CallbackInfo ci) {
        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isUtilityPredictionEnabled()) return;

        PacketInterceptor interceptor = HitregistryClient.getPacketInterceptor();
        if (interceptor == null) return;

        try {
            String typeKey = packet.getEntityType().toString();

            // ✅ FIX: no getId() in your version
            int entityId = packet.getEntityId();

            interceptor.onEntityAdded(entityId, typeKey);

        } catch (Exception e) {
            HitregistryClient.LOGGER.debug("[Hitregistry] AddEntity intercept error: {}", e.getMessage());
        }
    }

    @Inject(method = "onBlockUpdate", at = @At("RETURN"))
    private void hitregistry$onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        if (HitregistryClient.getConfig() == null) return;
        if (!HitregistryClient.getConfig().isBlockPlacementPredictionEnabled()) return;

        PacketInterceptor interceptor = HitregistryClient.getPacketInterceptor();
        if (interceptor == null) return;

        try {
            BlockPos pos = packet.getPos();
            interceptor.onBlockUpdate(pos.asLong());
        } catch (Exception e) {
            HitregistryClient.LOGGER.debug("[Hitregistry] BlockUpdate intercept error: {}", e.getMessage());
        }
    }
}