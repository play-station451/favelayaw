package net.favela.yaw.mixin;

import net.favela.yaw.impl.event.Events;
import net.favela.yaw.impl.event.events.StartBreakingBlockEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void yaw$onStartDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        StartBreakingBlockEvent event = Events.post(new StartBreakingBlockEvent(pos, direction));
        if (event.isCancelled()) {
            cir.setReturnValue(true);
        }
    }
}
