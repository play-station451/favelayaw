package net.favela.yaw.mixin;

import net.favela.yaw.impl.modules.categories.render.FullBright;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "nightVisionScale", at = @At("HEAD"), cancellable = true)
    private void yaw$onNightVisionScale(CallbackInfoReturnable<Float> cir) {
        if (FullBright.INSTANCE != null && FullBright.INSTANCE.isPotionsMode()) {
            cir.setReturnValue(1.0F);
        }
    }
}