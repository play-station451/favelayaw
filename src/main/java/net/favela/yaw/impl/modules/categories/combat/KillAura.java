package net.favela.yaw.impl.modules.categories.combat;

import com.google.auto.service.AutoService;
import net.favela.yaw.impl.modules.Module;
import net.favela.yaw.impl.setting.settings.EnumSetting;
import net.favela.yaw.impl.setting.settings.NumberSetting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import static net.favela.yaw.impl.util.wrapper.Wrapper.MC;

@AutoService(Module.class)
public class KillAura extends Module {

    public enum Mode {
        Normal, Spam;

        @Override
        public String toString() {
            return this == Spam ? "1.8" : "Normal";
        }
    }

    public enum PlayersOnly { True, False }

    public static KillAura INSTANCE;

    public final EnumSetting<Mode> mode = enm("Mode", Mode.Normal);
    public final NumberSetting range = num("Range", 1.0f, 6.0f, 4.0f);
    public final NumberSetting legacyCps = num("1.8CPS", 5.0f, 20.0f, 20.0f);
    public final EnumSetting<PlayersOnly> players = enm("PlayersOnly", PlayersOnly.False);

    private int tickCounter;

    public KillAura() {
        super("KillAura", "Automatically attacks nearby entities", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onTick() {
        if (MC.player == null || MC.level == null || MC.gameMode == null) return;

        Entity target = findTarget();
        if (target == null) return;

        switch (mode.get()) {
            case Normal -> handleNormal(target);
            case Spam -> handleSpam(target);
        }
    }

    private void handleNormal(Entity target) {
        if (MC.player.getAttackStrengthScale(1.0F) < 1.0F) return;
        attack(target);
    }

    private void handleSpam(Entity target) {
        int interval = Math.max(1, Math.round(20.0f / legacyCps.getFloat()));
        tickCounter++;
        if (tickCounter < interval) return;
        tickCounter = 0;

        attack(target);
    }

    private void attack(Entity target) {
        MC.gameMode.attack(MC.player, target);
        MC.player.swing(InteractionHand.MAIN_HAND);
    }

    private Entity findTarget() {
        double r = range.getFloat();
        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : MC.level.entitiesForRendering()) {
            if (entity == MC.player) continue;
            if (!(entity instanceof LivingEntity living) || living.isDeadOrDying()) continue;
            if (players.get() == PlayersOnly.True && !(entity instanceof Player)) continue;

            double dist = MC.player.distanceTo(entity);
            if (dist > r) continue;

            if (dist < bestDist) {
                bestDist = dist;
                best = entity;
            }
        }

        return best;
    }
}