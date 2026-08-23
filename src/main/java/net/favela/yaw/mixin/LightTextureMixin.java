package net.favela.yaw.mixin;

import net.favela.yaw.impl.modules.categories.render.FullBright;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@Mixin(LightTexture.class)
public abstract class LightTextureMixin {

    @Inject(method = "hasNightVision", at = @At("HEAD"), cancellable = true)
    private void yaw$onHasNightVision(CallbackInfoReturnable<Boolean> cir) {
        if (FullBright.INSTANCE != null && FullBright.INSTANCE.isPotionsMode()) {
            cir.setReturnValue(true);
        }
    }
}