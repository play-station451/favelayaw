package net.favela.yaw.mixin;

import net.favela.yaw.impl.modules.categories.movement.Velocity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@Mixin(ClientboundSetEntityMotionPacket.class)
public abstract class ClientboundSetEntityMotionPacketMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void yaw$onHandle(ClientGamePacketListener listener, CallbackInfo ci) {
        ClientboundSetEntityMotionPacket packet = (ClientboundSetEntityMotionPacket) (Object) this;

        if (MC.player == null || MC.level == null) return;
        if (packet.getId() != MC.player.getId()) return;
        if (Velocity.INSTANCE == null || !Velocity.INSTANCE.isEnabled()) return;

        ClientLevel level = MC.level;
        Entity entity = level.getEntity(packet.getId());
        if (entity == null) return;

        Vec3 movement = packet.getMovement();
        double[] modified = Velocity.INSTANCE.modify(movement.x, movement.y, movement.z);

        entity.setDeltaMovement(modified[0], modified[1], modified[2]);
        entity.hasImpulse = true;

        ci.cancel();
    }
}