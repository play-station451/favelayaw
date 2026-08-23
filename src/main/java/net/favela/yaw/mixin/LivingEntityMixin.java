package net.favela.yaw.mixin;

import net.favela.yaw.impl.modules.categories.render.FullBright;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "hasEffect", at = @At("HEAD"), cancellable = true)
    private void yaw$onHasEffect(Holder<MobEffect> effect, CallbackInfoReturnable<Boolean> cir) {
        if (MC.player == null || (Object) this != MC.player) return;
        if (FullBright.INSTANCE == null || !FullBright.INSTANCE.isPotionsMode()) return;
        if (effect.is(MobEffects.NIGHT_VISION)) {
            cir.setReturnValue(true);
        }
    }
}