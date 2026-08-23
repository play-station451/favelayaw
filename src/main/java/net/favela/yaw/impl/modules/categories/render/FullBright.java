package net.favela.yaw.impl.modules.categories.render;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.modules.Module;
import net.minecraft.client.OptionInstance;

import java.lang.reflect.Field;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class FullBright extends Module {

    public static FullBright INSTANCE;

    private static Field valueField;

    public FullBright() {
        super("FullBright", "Brightens the world", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        forceGamma(1000000.0);
    }

    @Override
    public void onDisable() {
        forceGamma(1.0);
    }

    private void forceGamma(double value) {
        try {
            OptionInstance<Double> gamma = MC.options.gamma();
            if (valueField == null) {
                valueField = OptionInstance.class.getDeclaredField("value");
                valueField.setAccessible(true);
            }
            valueField.set(gamma, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}