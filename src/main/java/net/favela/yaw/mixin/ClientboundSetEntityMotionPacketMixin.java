package net.favela.yaw.mixins;

import net.favela.yaw.impl.modules.categories.movement.Velocity;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@Mixin(ClientboundSetEntityMotionPacket.class)
public abstract class ClientboundSetEntityMotionPacketMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/entity/Entity;DDD)V", at = @At("TAIL"))
    private void yaw$onConstruct(Entity entity, double x, double y, double z, CallbackInfo ci) {
        if (MC.player == null || entity.getId() != MC.player.getId()) return;
        if (Velocity.INSTANCE == null || !Velocity.INSTANCE.isEnabled()) return;

        double[] modified = Velocity.INSTANCE.modify(x, y, z);
    }
}