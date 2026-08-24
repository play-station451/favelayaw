package net.favela.yaw.impl.modules.categories.player;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.BooleanSetting;
import net.favela.yaw.impl.setting.settings.EnumSetting;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class SpeedMine extends Module {

    public enum Mode { Single, Double, Quadruple }

    public static SpeedMine INSTANCE;

    public final EnumSetting<Mode> mode = enm("Mode", Mode.Double);
    public final BooleanSetting instantRebreak = register(new BooleanSetting("InstantRebreak", "Instantly allows rebreaking after a block breaks", true));

    public SpeedMine() {
        super("SpeedMine", "Increases block breaking speed", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.gameMode == null || MC.level == null) return;
        if (!MC.options.keyAttack.isDown()) return;
        if (!(MC.hitResult instanceof BlockHitResult blockHit)) return;
        if (blockHit.getType() != HitResult.Type.BLOCK) return;

        var pos = blockHit.getBlockPos();
        Direction face = blockHit.getDirection();

        int extraHits = switch (mode.get()) {
            case Single -> 0;
            case Double -> 1;
            case Quadruple -> 3;
        };

        for (int i = 0; i < extraHits; i++) {
            MC.gameMode.continueDestroyBlock(pos, face);
        }
    }

    public boolean isInstantRebreak() {
        return isEnabled() && instantRebreak.get();
    }
}