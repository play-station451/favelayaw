package net.favela.yaw.impl.modules.categories.movement;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.EnumSetting;
import net.favela.yaw.impl.setting.settings.NumberSetting;
import net.minecraft.client.player.LocalPlayer;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class Velocity extends Module {

    public enum Mode { Vanilla, Grim, NCP }

    public static Velocity INSTANCE;

    public final EnumSetting<Mode> mode = enm("Mode", Mode.Grim);
    public final NumberSetting horizontal = num("Horizontal", 0.0f, 1.0f, 0.35f);
    public final NumberSetting vertical = num("Vertical", 0.0f, 1.0f, 0.4f);

    public Velocity() {
        super("Velocity", "Anti knockback", Category.MOVEMENT);
        INSTANCE = this;
    }

    @Override
    public void onTick() {
        if (MC.player == null) return;
    }

    @Override
    public void onDisable() {
    }

    public double[] modify(double x, double y, double z) {
        if (!isEnabled()) return new double[]{x, y, z};

        double h;
        double v;
        switch (mode.get()) {
            case Vanilla -> {
                h = 0.0D;
                v = 0.0D;
            }
            case NCP -> {
                h = 0.15D;
                v = 0.2D;
            }
            default -> {
                h = horizontal.getFloat();
                v = vertical.getFloat();
            }
        }

        return new double[]{
                x * h,
                y * v,
                z * h
        };
    }
}