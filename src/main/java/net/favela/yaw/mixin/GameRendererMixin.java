package net.favela.yaw.mixin;

import net.favela.yaw.impl.modules.categories.render.FullBright;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "nightVisionScale", at = @At("HEAD"), cancellable = true)
    private static void yaw$onNightVisionScale(LivingEntity entity, float partialTick, CallbackInfoReturnable<Float> cir) {
        boolean active = FullBright.INSTANCE != null && FullBright.INSTANCE.isPotionsMode();
        System.out.println("[yaw] nightVisionScale hit, potionsMode=" + active);

        if (active) {
            cir.setReturnValue(1.0F);
        }
    }
}