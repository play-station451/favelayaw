package net.favela.yaw.impl.modules.categories.render;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.EnumSetting;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class FullBright extends Module {

    public enum Mode { Gamma, Potions }

    public static FullBright INSTANCE;

    public final EnumSetting<Mode> mode = enm("Mode", Mode.Gamma);

    private double prevGamma;
    private boolean hasPrevGamma;

    public FullBright() {
        super("FullBright", "Brightens the world", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        if (mode.get() == Mode.Gamma) {
            applyGamma();
        }
    }

    @Override
    public void onTick() {
        if (mode.get() == Mode.Gamma) {
            applyGamma();
        } else if (hasPrevGamma) {
            restoreGamma();
        }
    }

    @Override
    public void onDisable() {
        restoreGamma();
    }

    private void applyGamma() {
        if (MC.options == null) return;
        if (!hasPrevGamma) {
            prevGamma = MC.options.gamma().get();
            hasPrevGamma = true;
        }
        MC.options.gamma().set(1000.0D);
    }

    private void restoreGamma() {
        if (MC.options == null || !hasPrevGamma) return;
        MC.options.gamma().set(prevGamma);
        hasPrevGamma = false;
    }

    public boolean isPotionsMode() {
        return isEnabled() && mode.get() == Mode.Potions;
    }
}